package mezel

import fs2.*
import cats.effect.*
import fs2.io.file.*
import cats.*
import com.google.devtools.build.lib.buildeventstream.{build_event_stream => bes}
import scala.concurrent.duration._

def parseBEP(path: Path): Stream[IO, bes.BuildEvent] = {
  Files[IO].tail(path, pollDelay = 50.millis).through(fs2.io.toInputStream[IO]).flatMap { is =>
    Stream
      .repeatEval(IO.blocking(bes.BuildEvent.parseDelimitedFrom(is)))
      .unNoneTerminate
      .takeWhile(x => !x.payload.isFinished)
  }
}
