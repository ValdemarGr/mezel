package mezel

import com.google.devtools.build.lib.query2.proto.proto2api.build
import com.google.devtools.build.lib.analysis.analysis_v2
import cats.implicits.*
import io.circe.parser.*
import io.circe.*
import fs2.*
import cats.effect.*
import fs2.io.file.*
import cats.parse.Parser as P
import cats.parse.Parser0 as P0
import cats.parse.Rfc5234 as Rfc
import cats.parse.Numbers as Num
import _root_.io.circe.Json
import cats.data.*
import fs2.concurrent.SignallingRef
import catcheffect.*
import cats.*
import cats.derived.*
import alleycats.*
import java.nio.file.Paths
import java.net.URI
import com.google.devtools.build.lib.analysis.analysis_v2.PathFragment
import fs2.concurrent.Channel
import _root_.io.bazel.rules_scala.diagnostics.diagnostics
import cats.effect.std.Supervisor
import com.google.devtools.build.lib.buildeventstream.{build_event_stream => bes}
import scala.concurrent.duration._
import fs2.io.Watcher
import com.google.protobuf.CodedInputStream

def parseBEP(path: Path): Stream[IO, bes.BuildEvent] =
  Files[IO].tail(path, pollDelay = 50.millis).through(fs2.io.toInputStream[IO]).flatMap { is =>
    Stream
      .repeatEval(IO.blocking(bes.BuildEvent.parseDelimitedFrom(is)))
      .unNoneTerminate
      .takeWhile(x => !x.payload.isFinished)
  }
