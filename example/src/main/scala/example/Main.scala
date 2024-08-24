package example

import cats.effect._
import hxl._

object Main extends IOApp.Simple {
  Hxl

  def run = IO.unit

  val a = {
    val b = 2
    val c = example.dep.Dep.value

    // cats.effect.IO
    // val c = false + b
    1
  }
  println("hello world")
}
