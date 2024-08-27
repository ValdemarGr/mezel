package mezel

enum Verbosity(val level: Int) {
  case Normal extends Verbosity(0)
  case Verbose extends Verbosity(1)
  case Debug extends Verbosity(2)
  case Trace extends Verbosity(3)
}

object Verbosity {
  def fromInt(level: Int): Verbosity = 
    values.find(_.level == level).getOrElse(Trace)
}
