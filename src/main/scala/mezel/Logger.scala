package mezel

import io.circe.*
import io.circe.syntax.*
import cats.effect.*

trait Logger {
  def logLog(msg: String): IO[Unit]

  def logInfo(msg: String): IO[Unit]

  def logWarn(msg: String): IO[Unit]

  def logError(msg: String): IO[Unit]

  def printStdOut(msg: String): IO[Unit]

  def printStdErr(msg: String): IO[Unit]
}

object Logger {
  def make(
      taskId: Option[TaskId],
      originId: Option[String]
  )(send: Notification => IO[Unit]) =
    new Logger {
      def sendNotification[A: Encoder](method: String, params: A): IO[Unit] =
        send(Notification("2.0", method, Some(params.asJson))).void

      def sendLog(msg: String, messageType: MessageType): IO[Unit] =
        sendNotification(
          "build/logMessage",
          LogMessageParams(messageType, taskId, originId, msg)
        )

      override def printStdOut(msg: String): IO[Unit] =
        originId match {
          case None => logInfo(msg)
          case Some(x) =>
            sendNotification(
              "run/printStdout",
              PrintParams(x, taskId, msg)
            )
        }

      override def printStdErr(msg: String): IO[Unit] =
        originId match {
          case None => logInfo(msg)
          case Some(x) =>
            sendNotification(
              "run/printStderr",
              PrintParams(x, taskId, msg)
            )
        }

      override def logWarn(msg: String): IO[Unit] =
        sendLog(msg, MessageType.Warning)

      override def logInfo(msg: String): IO[Unit] =
        sendLog(msg, MessageType.Info)

      override def logLog(msg: String): IO[Unit] =
        sendLog(msg, MessageType.Log)

      override def logError(msg: String): IO[Unit] =
        sendLog(msg, MessageType.Error)
    }
}
