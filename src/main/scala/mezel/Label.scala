package mezel

import io.circe._

final case class Label private (val value: String)

object Label {
  def unsafe(value: String): Label = new Label(value)

  def parse(value: String): Label = 
    if (value.startsWith("@")) unsafe(value) else unsafe("@" + value)

  given Decoder[Label] = Decoder[String].map(parse)
  given Encoder[Label] = Encoder[String].contramap[Label](_.value)
}
