package me.maciejb.snappyflows


import akka.NotUsed
import akka.stream.io.ByteStringParser
import akka.stream.io.ByteStringParser.{ParseResult, ByteReader, ParseStep}
import akka.stream.scaladsl._
import akka.stream.{Attributes, FlowShape}
import akka.util.ByteString
import me.maciejb.snappyflows.impl.{Chunking, Int24}
import org.xerial.snappy.{PureJavaCrc32C, Snappy}

import scala.concurrent.{ExecutionContext, Future}

object SnappyFlows {

  val MaxChunkSize = 65536
  val DefaultChunkSize = MaxChunkSize

  def decompress(verifyChecksums: Boolean = true): Flow[ByteString, ByteString, NotUsed] = {
    SnappyChunk.decodingFlow.map {
      case NoData => ByteString.empty
      case UncompressedData(data, checksum) =>
        val dataBytes = data.toArray
        if (verifyChecksums) SnappyChecksum.verifyChecksum(dataBytes, checksum)
        data
      case CompressedData(data, checksum) =>
        val uncompressedData = Snappy.uncompress(data.toArray)
        if (verifyChecksums) SnappyChecksum.verifyChecksum(uncompressedData, checksum)
        ByteString.fromArray(uncompressedData)
    }
  }

  def decompressAsync(parallelism: Int, verifyChecksums: Boolean = true)
                     (implicit ec: ExecutionContext): Flow[ByteString, ByteString, NotUsed] = {
    SnappyChunk.decodingFlow.mapAsync(parallelism) {
      case NoData => Future.successful(ByteString.empty)
      case UncompressedData(data, checksum) =>
        Future {
          val dataBytes = data.toArray
          if (verifyChecksums) SnappyChecksum.verifyChecksum(dataBytes, checksum)
          data
        }
      case CompressedData(data, checksum) =>
        Future {
          val uncompressedData = Snappy.uncompress(data.toArray)
          if (verifyChecksums) SnappyChecksum.verifyChecksum(uncompressedData, checksum)
          ByteString.fromArray(uncompressedData)
        }
    }
  }

  private[this] def compressWithFlow(chunkSize: Int,
                                     compressionFlow: Flow[ByteString, ByteString, NotUsed]) = {
    require(chunkSize <= MaxChunkSize, s"Chunk size $chunkSize exceeds maximum chunk size of $MaxChunkSize.")

    val headerSource = Source.single(SnappyFramed.Header)
    val chunkingAndCompression = Flow[ByteString].via(Chunking.fixedSize(chunkSize)).via(compressionFlow)

    Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val concat = b.add(Concat[ByteString](2))
      val flow = b.add(Flow[ByteString])

      headerSource ~> concat.in(0)
      flow.outlet ~> chunkingAndCompression ~> concat.in(1)

      FlowShape(flow.in, concat.out)
    })
  }

  def compress(chunkSize: Int = DefaultChunkSize): Flow[ByteString, ByteString, NotUsed] = {
    compressWithFlow(chunkSize, Flow[ByteString].map(SnappyFramed.compressChunk))
  }

  def compressAsync(parallelism: Int, chunkSize: Int = DefaultChunkSize)
                   (implicit ec: ExecutionContext): Flow[ByteString, ByteString, NotUsed] = {
    compressWithFlow(chunkSize,
      Flow[ByteString].mapAsync(parallelism) { chunk => Future(SnappyFramed.compressChunk(chunk)) }
    )
  }

}

object SnappyFramed {
  object Flags {
    val StreamIdentifier = 0xff.toByte
    val UncompressedData = 0x01.toByte
    val CompressedData = 0x00.toByte
  }

  private val HeaderBytes =
    Array[Byte](Flags.StreamIdentifier, 0x06, 0x00, 0x00, 0x73, 0x4e, 0x61, 0x50, 0x70, 0x59)

  val Header = ByteString(HeaderBytes)

  def compressChunk(chunk: ByteString): ByteString = {
    val chunkBytes = chunk.toArray
    val compressed = Snappy.compress(chunkBytes)
    val checksum = SnappyChecksum.checksum(chunkBytes)
    val length = Int24.writeLE(compressed.length + 4)

    ByteString.newBuilder
      .putByte(SnappyFramed.Flags.CompressedData)
      .append(length)
      .putInt(checksum)
      .putBytes(compressed)
      .result()
  }

}

sealed trait SnappyChunk
case class CompressedData(data: ByteString, checksum: Int) extends SnappyChunk
case class UncompressedData(data: ByteString, checksum: Int) extends SnappyChunk
case object NoData extends SnappyChunk

object SnappyChunk {
  def decodingFlow: Flow[ByteString, SnappyChunk, NotUsed] = Flow.fromGraph(new Decoder)

  private class Decoder extends ByteStringParser[SnappyChunk] {
    override def createLogic(inheritedAttributes: Attributes) = new ParsingLogic {

      object HeaderParse extends ParseStep[SnappyChunk] {
        override def parse(reader: ByteReader) = {
          val header = reader.take(SnappyFramed.Header.length)

          if (header == SnappyFramed.Header) ParseResult(Some(NoData), ChunkParser)
          else sys.error(s"Illegal header: $header.")
        }
      }

      object ChunkParser extends ParseStep[SnappyChunk] {

        override def parse(reader: ByteReader) = {
          reader.readByte() match {
            case SnappyFramed.Flags.CompressedData =>
              val segmentLength = Int24.readLE(reader) - 4
              val checksum = reader.readIntLE()
              val data = reader.take(segmentLength)
              ParseResult(Some(CompressedData(data, checksum)), ChunkParser)
            case SnappyFramed.Flags.UncompressedData =>
              val segmentLength = Int24.readLE(reader) - 4
              val checksum = reader.readIntLE()
              val data = reader.take(segmentLength)
              ParseResult(Some(UncompressedData(data, checksum)), ChunkParser)
            case flag => throw new IllegalChunkFlag(flag)
          }
        }
      }

      startWith(HeaderParse)
    }
  }
}

object SnappyChecksum {
  val MaskDelta = 0xa282ead8

  private[this] final val threadLocalCrc = new ThreadLocal[PureJavaCrc32C]() {
    override def initialValue() = new PureJavaCrc32C
  }

  def checksum(data: Array[Byte]): Int = {
    val crc32c = threadLocalCrc.get()
    crc32c.reset()
    crc32c.update(data, 0, data.length)
    val crc = crc32c.getIntegerValue
    ((crc >>> 15) | (crc << 17)) + MaskDelta
  }

  def verifyChecksum(data: Array[Byte], expectedChecksum: Int) = {
    val actual = checksum(data)
    if (actual != expectedChecksum) throw new InvalidChecksum(expectedChecksum, actual)
  }

}
