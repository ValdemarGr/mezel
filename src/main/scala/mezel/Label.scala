package mezel

import cats.implicits._
import io.circe._

final case class Label private (val value: String)

object Label {
  def unsafe(value: String): Label = new Label(value)

  def parse(value: String): Label = 
    unsafe("@" + value.dropWhile(_ === '@'))

  given Decoder[Label] = Decoder[String].map(parse)
  given Encoder[Label] = Encoder[String].contramap[Label](_.value)
}
