package example

import cats.effect._

object Main extends App {
  val a = {
    val b = 2

    // cats.effect.IO
    val c = false + b
    1
  }
  println("hello world")
}
