package mezel

import io.circe._

final case class LogMessageParams(
    `type`: MessageType,
    task: TaskId,
    originId: Option[String],
    message: String
) derives Encoder.AsObject

enum MessageType:
  case Error, Warning, Info, Log

object MessageType:
  given Encoder[MessageType] = Encoder[Int].contramap {
    case MessageType.Error   => 1
    case MessageType.Warning => 2
    case MessageType.Info    => 3
    case MessageType.Log     => 4
  }

final case class TextDocumentIdentifier(
    uri: SafeUri
) derives Encoder.AsObject

final case class PublishDiagnosticsParams(
    textDocument: TextDocumentIdentifier,
    buildTarget: BuildTargetIdentifier,
    originId: Option[String],
    diagnostics: List[Diagnostic],
    reset: Boolean
) derives Encoder.AsObject

final case class Code(value: String | Int) extends AnyVal
object Code:
  given Encoder[Code] = Encoder.instance[Code] { code =>
    code.value match {
      case s: String => Encoder[String].apply(s)
      case i: Int    => Encoder[Int].apply(i)
    }
  }

final case class Diagnostic(
    range: Range,
    severity: Option[DiagnosticSeverity],
    code: Option[Code],
    codeDestription: Option[CodeDescription],
    source: Option[String],
    message: String,
    tags: Option[List[Int]],
    relatedInformation: Option[List[DiagnosticRelatedInformation]],
    dataKind: Option[String],
    data: Option[ScalaDiagnostic]
) derives Encoder.AsObject

final case class DiagnosticRelatedInformation(
    location: Location,
    message: String
) derives Encoder.AsObject

final case class ScalaDiagnostic(
    actions: Option[List[ScalaAction]]
) derives Encoder.AsObject

final case class ScalaAction(
    title: String,
    description: Option[String],
    edit: Option[ScalaWorkspaceEdit]
) derives Encoder.AsObject

final case class ScalaWorkspaceEdit(
    changes: List[ScalaTextEdit]
) derives Encoder.AsObject

final case class ScalaTextEdit(
    range: Range,
    newText: String
) derives Encoder.AsObject

enum DiagnosticSeverity:
  case Error, Warning, Information, Hint

object DiagnosticSeverity:
  given Decoder[DiagnosticSeverity] = Decoder[Int].emap {
    case 1 => Right(DiagnosticSeverity.Error)
    case 2 => Right(DiagnosticSeverity.Warning)
    case 3 => Right(DiagnosticSeverity.Information)
    case 4 => Right(DiagnosticSeverity.Hint)
    case i => Left(s"invalid diagnostic severity: ${i}")
  }

  given Encoder[DiagnosticSeverity] = Encoder[Int].contramap {
    case DiagnosticSeverity.Error       => 1
    case DiagnosticSeverity.Warning     => 2
    case DiagnosticSeverity.Information => 3
    case DiagnosticSeverity.Hint        => 4
  }

final case class CodeDescription(
    href: SafeUri
) derives Encoder.AsObject
