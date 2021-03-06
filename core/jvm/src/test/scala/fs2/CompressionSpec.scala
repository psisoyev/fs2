package fs2

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip._

import cats.effect._
import fs2.Stream._

import fs2.compression._

import scala.collection.mutable

class CompressionSpec extends Fs2Spec {
  def getBytes(s: String): Array[Byte] =
    s.getBytes

  def deflateStream(b: Array[Byte], level: Int, strategy: Int, nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val deflater = new Deflater(level, nowrap)
    deflater.setStrategy(strategy)
    val deflaterStream = new DeflaterOutputStream(byteArrayStream, deflater)
    deflaterStream.write(b)
    deflaterStream.close()
    byteArrayStream.toByteArray
  }

  def inflateStream(b: Array[Byte], nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val inflaterStream =
      new InflaterOutputStream(byteArrayStream, new Inflater(nowrap))
    inflaterStream.write(b)
    inflaterStream.close()
    byteArrayStream.toByteArray
  }

  "Compress" - {
    "deflate input" in forAll(strings, intsBetween(0, 9), intsBetween(0, 2), booleans) {
      (s: String, level: Int, strategy: Int, nowrap: Boolean) =>
        val expected = deflateStream(getBytes(s), level, strategy, nowrap).toVector
        Stream
          .chunk[IO, Byte](Chunk.bytes(getBytes(s)))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            deflate(
              level = level,
              strategy = strategy,
              nowrap = nowrap
            )
          )
          .compile
          .toVector
          .asserting(actual => assert(actual == expected))
    }

    "inflate input" in forAll(strings, intsBetween(0, 9), intsBetween(0, 2), booleans) {
      (s: String, level: Int, strategy: Int, nowrap: Boolean) =>
        Stream
          .chunk[IO, Byte](Chunk.bytes(getBytes(s)))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            deflate(
              level = level,
              strategy = strategy,
              nowrap = nowrap
            )
          )
          .compile
          .to(Array)
          .flatMap { deflated =>
            val expected = inflateStream(deflated, nowrap).toVector
            Stream
              .chunk[IO, Byte](Chunk.bytes(deflated))
              .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
              .through(inflate(nowrap = nowrap))
              .compile
              .toVector
              .asserting(actual => assert(actual == expected))
          }
    }

    "deflate |> inflate ~= id" in forAll { s: Stream[Pure, Byte] =>
      s.covary[IO]
        .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
        .through(deflate())
        .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
        .through(inflate())
        .compile
        .toVector
        .asserting(it => assert(it == s.toVector))
    }

    "deflate.compresses input" in {
      val uncompressed =
        getBytes(""""
                   |"A type system is a tractable syntactic method for proving the absence
                   |of certain program behaviors by classifying phrases according to the
                   |kinds of values they compute."
                   |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
      Stream
        .chunk[IO, Byte](Chunk.bytes(uncompressed))
        .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
        .through(deflate(9))
        .compile
        .toVector
        .asserting(compressed => assert(compressed.length < uncompressed.length))
    }

    "deflate and inflate are reusable" in {
      val bytesIn: Int = 1024 * 1024
      val chunkSize = 1024
      val deflater = deflate[IO](bufferSize = chunkSize)
      val inflater = inflate[IO](bufferSize = chunkSize)
      val stream = Stream
        .chunk[IO, Byte](Chunk.Bytes(1.to(bytesIn).map(_.toByte).toArray))
        .through(deflater)
        .through(inflater)
      for {
        first <- stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
        second <- stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
      } yield {
        assert(first == second)
      }
    }

    "gzip |> gunzip ~= id" in forAll(
      strings,
      intsBetween(0, 9),
      intsBetween(0, 2),
      intsBetween(0, Int.MaxValue),
      strings,
      strings
    ) {
      (
          s: String,
          level: Int,
          strategy: Int,
          epochSeconds: Int,
          fileName: String,
          comment: String
      ) =>
        val expectedFileName = Option(toEncodableFileName(fileName))
        val expectedComment = Option(toEncodableComment(comment))
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds))
        Stream
          .chunk(Chunk.bytes(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gzip[IO](
              8192,
              deflateLevel = Some(level),
              deflateStrategy = Some(strategy),
              modificationTime = Some(Instant.ofEpochSecond(epochSeconds)),
              fileName = Some(fileName),
              comment = Some(comment)
            )
          )
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gunzip[IO](8192)
          )
          .flatMap { gunzipResult =>
            assert(gunzipResult.fileName == expectedFileName)
            assert(gunzipResult.comment == expectedComment)
            if (epochSeconds != 0) assert(gunzipResult.modificationTime == expectedMTime)
            gunzipResult.content
          }
          .compile
          .toVector
          .asserting(bytes => assert(bytes == s.getBytes.toSeq))
    }

    "gzip |> gunzip ~= id (mutually prime chunk sizes, compression larger)" in forAll(
      strings,
      intsBetween(0, 9),
      intsBetween(0, 2),
      intsBetween(0, Int.MaxValue),
      strings,
      strings
    ) {
      (
          s: String,
          level: Int,
          strategy: Int,
          epochSeconds: Int,
          fileName: String,
          comment: String
      ) =>
        val expectedFileName = Option(toEncodableFileName(fileName))
        val expectedComment = Option(toEncodableComment(comment))
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds))
        Stream
          .chunk(Chunk.bytes(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gzip[IO](
              1031,
              deflateLevel = Some(level),
              deflateStrategy = Some(strategy),
              modificationTime = Some(Instant.ofEpochSecond(epochSeconds)),
              fileName = Some(fileName),
              comment = Some(comment)
            )
          )
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gunzip[IO](509)
          )
          .flatMap { gunzipResult =>
            assert(gunzipResult.fileName == expectedFileName)
            assert(gunzipResult.comment == expectedComment)
            if (epochSeconds != 0) assert(gunzipResult.modificationTime == expectedMTime)
            gunzipResult.content
          }
          .compile
          .toVector
          .asserting(bytes => assert(bytes == s.getBytes.toSeq))
    }

    "gzip |> gunzip ~= id (mutually prime chunk sizes, decompression larger)" in forAll(
      strings,
      intsBetween(0, 9),
      intsBetween(0, 2),
      intsBetween(0, Int.MaxValue),
      strings,
      strings
    ) {
      (
          s: String,
          level: Int,
          strategy: Int,
          epochSeconds: Int,
          fileName: String,
          comment: String
      ) =>
        val expectedFileName = Option(toEncodableFileName(fileName))
        val expectedComment = Option(toEncodableComment(comment))
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds))
        Stream
          .chunk(Chunk.bytes(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gzip[IO](
              509,
              deflateLevel = Some(level),
              deflateStrategy = Some(strategy),
              modificationTime = Some(Instant.ofEpochSecond(epochSeconds)),
              fileName = Some(fileName),
              comment = Some(comment)
            )
          )
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gunzip[IO](1031)
          )
          .flatMap { gunzipResult =>
            assert(gunzipResult.fileName == expectedFileName)
            assert(gunzipResult.comment == expectedComment)
            if (epochSeconds != 0) assert(gunzipResult.modificationTime == expectedMTime)
            gunzipResult.content
          }
          .compile
          .toVector
          .asserting(bytes => assert(bytes == s.getBytes.toSeq))
    }

    "gzip |> GZIPInputStream ~= id" in forAll(
      strings,
      intsBetween(0, 9),
      intsBetween(0, 2),
      intsBetween(0, Int.MaxValue),
      strings,
      strings
    ) {
      (
          s: String,
          level: Int,
          strategy: Int,
          epochSeconds: Int,
          fileName: String,
          comment: String
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.bytes(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gzip(
              1024,
              deflateLevel = Some(level),
              deflateStrategy = Some(strategy),
              modificationTime = Some(Instant.ofEpochSecond(epochSeconds)),
              fileName = Some(fileName),
              comment = Some(comment)
            )
          )
          .compile
          .to(Array)
          .asserting { bytes =>
            val bis = new ByteArrayInputStream(bytes)
            val gzis = new GZIPInputStream(bis)

            val buffer = mutable.ArrayBuffer[Byte]()
            var read = gzis.read()
            while (read >= 0) {
              buffer += read.toByte
              read = gzis.read()
            }

            assert(buffer.toVector == s.getBytes.toVector)
          }
    }

    "gzip.compresses input" in {
      val uncompressed =
        getBytes(""""
                   |"A type system is a tractable syntactic method for proving the absence
                   |of certain program behaviors by classifying phrases according to the
                   |kinds of values they compute."
                   |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
      Stream
        .chunk[IO, Byte](Chunk.bytes(uncompressed))
        .through(gzip(2048))
        .compile
        .toVector
        .asserting(compressed => assert(compressed.length < uncompressed.length))
    }

    "gunzip limit fileName and comment length" in {
      val longString
          : String = Array.fill(1024 * 1024 + 1)("x").mkString("") // max(classic.fileNameBytesSoftLimit, classic.fileCommentBytesSoftLimit) + 1
      val expectedFileName = Option(toEncodableFileName(longString))
      val expectedComment = Option(toEncodableComment(longString))
      Stream
        .chunk(Chunk.empty[Byte])
        .through(gzip[IO](8192, fileName = Some(longString), comment = Some(longString)))
        .unchunk // ensure chunk sizes are less than file name and comment size soft limits
        .through(gunzip[IO](8192))
        .flatMap { gunzipResult =>
          assert(
            gunzipResult.fileName
              .map(_.length)
              .getOrElse(0) < expectedFileName.map(_.length).getOrElse(0)
          )
          assert(
            gunzipResult.comment
              .map(_.length)
              .getOrElse(0) < expectedComment.map(_.length).getOrElse(0)
          )
          gunzipResult.content
        }
        .compile
        .toVector
        .asserting(vector => assert(vector.isEmpty))
    }

    "unix.gzip |> gunzip" in {
      val expectedContent = "fs2.compress implementing RFC 1952\n"
      val expectedFileName = Option(toEncodableFileName("fs2.compress"))
      val expectedComment = Option.empty[String]
      val expectedMTime = Option(Instant.parse("2020-02-04T22:00:02Z"))
      val compressed = Array(0x1f, 0x8b, 0x08, 0x08, 0x62, 0xe9, 0x39, 0x5e, 0x00, 0x03, 0x66, 0x73,
        0x32, 0x2e, 0x63, 0x6f, 0x6d, 0x70, 0x72, 0x65, 0x73, 0x73, 0x00, 0x4b, 0x2b, 0x36, 0xd2,
        0x4b, 0xce, 0xcf, 0x2d, 0x28, 0x4a, 0x2d, 0x2e, 0x56, 0xc8, 0xcc, 0x2d, 0xc8, 0x49, 0xcd,
        0x4d, 0xcd, 0x2b, 0xc9, 0xcc, 0x4b, 0x57, 0x08, 0x72, 0x73, 0x56, 0x30, 0xb4, 0x34, 0x35,
        0xe2, 0x02, 0x00, 0x57, 0xb3, 0x5e, 0x6d, 0x23, 0x00, 0x00, 0x00).map(_.toByte)
      Stream
        .chunk(Chunk.bytes(compressed))
        .through(
          gunzip[IO]()
        )
        .flatMap { gunzipResult =>
          assert(gunzipResult.fileName == expectedFileName)
          assert(gunzipResult.comment == expectedComment)
          assert(gunzipResult.modificationTime == expectedMTime)
          gunzipResult.content
        }
        .compile
        .toVector
        .asserting { vector =>
          assert(new String(vector.toArray, StandardCharsets.US_ASCII) == expectedContent)
        }
    }

    "gzip and gunzip are reusable" in {
      val bytesIn: Int = 1024 * 1024
      val chunkSize = 1024
      val gzipStream = gzip[IO](bufferSize = chunkSize)
      val gunzipStream = gunzip[IO](bufferSize = chunkSize)
      val stream = Stream
        .chunk[IO, Byte](Chunk.Bytes(1.to(bytesIn).map(_.toByte).toArray))
        .through(gzipStream)
        .through(gunzipStream)
        .flatMap(_.content)
      for {
        first <- stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
        second <- stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
      } yield {
        assert(first == second)
      }
    }

    def toEncodableFileName(fileName: String): String =
      new String(
        fileName.replaceAll("\u0000", "_").getBytes(StandardCharsets.ISO_8859_1),
        StandardCharsets.ISO_8859_1
      )

    def toEncodableComment(comment: String): String =
      new String(
        comment.replaceAll("\u0000", " ").getBytes(StandardCharsets.ISO_8859_1),
        StandardCharsets.ISO_8859_1
      )

  }
}
