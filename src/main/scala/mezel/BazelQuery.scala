package mezel

import cats.implicits.*
import cats.data.*
import cats.Eval

trait AnyQuery:
  def render: String

enum BinOp:
  case Intersect
  case Union
  case Except

enum Query extends AnyQuery:
  case Word(value: String)
  case Integer(value: Int)
  case Var(name: String)
  case Deps(q: Query, depth: Option[Integer])
  case RDeps(u: Query, x: Query, depth: Option[Integer])
  case AllRDeps(u: Query, depth: Option[Integer])
  case SamePkgDirectRDeps(u: Query)
  case Siblings(u: Query)
  case Some(q: Query, count: Option[Integer])
  case SomePaths(s: Query, e: Query)
  case AllPaths(s: Query, e: Query)
  case Kind(w: Word, input: Query)
  case Filter(w: Word, input: Query)
  case Attr(name: Word, pattern: Word, input: Query)
  case Visible(predicate: Query, input: Query)
  case Labels(attrName: Word, input: Query)
  case Tests(input: Query)
  case Buildfiles(input: Query)
  case RBuildfiles(ws: NonEmptyList[Word])
  case Loadfiles(input: Query)
  case Let(name: String, value: Query, in: Query)
  case Parens(q: Query)
  case Binary(left: Query, op: BinOp, right: Query)
  case Set(qs: List[Query])
  case Inputs(w: Word, q: Query)
  case Outputs(w: Word, q: Query)
  case Mnemonic(w: Word, q: Query)

  def render: String = renderQuery(this)

object dsl:
  import Query._
  def unify(q: Query | String): Query = q match
    case q: Query  => q
    case s: String => Word(s)

  def inputs(w: String)(q: Query | String): Query =
    Inputs(Word(w), unify(q))

  def outputs(w: String)(q: Query | String): Query =
    Outputs(Word(w), unify(q))

  def mnemonic(w: String)(q: Query | String): Query =
    Mnemonic(Word(w), unify(q))

  def deps(q: Query | String, depth: Option[Int] = None): Query =
    Deps(unify(q), depth.map(Integer(_)))

  def rdeps(u: Query | String, x: Query | String, depth: Option[Int] = None): Query =
    RDeps(unify(u), unify(x), depth.map(Integer(_)))

  def allrdeps(u: Query | String, depth: Option[Int] = None): Query =
    AllRDeps(unify(u), depth.map(Integer(_)))

  def samePkgDirectRDeps(u: Query | String): Query =
    SamePkgDirectRDeps(unify(u))

  def siblings(u: Query | String): Query =
    Siblings(unify(u))

  def some(q: Query | String, count: Option[Int] = None): Query =
    Some(unify(q), count.map(Integer(_)))

  def somePaths(s: Query | String, e: Query | String): Query =
    SomePaths(unify(s), unify(e))

  def allPaths(s: Query | String, e: Query | String): Query =
    AllPaths(unify(s), unify(e))

  def kind(w: String)(input: Query | String): Query =
    Kind(Word(w), unify(input))

  def filter(w: String, input: Query | String): Query =
    Filter(Word(w), unify(input))

  def attr(name: String, pattern: String, input: Query | String): Query =
    Attr(Word(name), Word(pattern), unify(input))

  def visible(predicate: Query | String, input: Query | String): Query =
    Visible(unify(predicate), unify(input))

  def labels(attrName: String, input: Query | String): Query =
    Labels(Word(attrName), unify(input))

  def tests(input: Query | String): Query =
    Tests(unify(input))

  def buildfiles(input: Query | String): Query =
    Buildfiles(unify(input))

  def rbuildfiles(ws: NonEmptyList[String]): Query =
    RBuildfiles(ws.map(Word(_)))

  def loadfiles(input: Query | String): Query =
    Loadfiles(unify(input))

  def let(name: String)(value: Query | String)(in: Var => Query | String): Query =
    Let(name, unify(value), unify(in(Var(name))))

  def parens(q: Query | String): Query =
    Parens(unify(q))

  def intersect(left: Query | String, right: Query | String): Query =
    Binary(unify(left), BinOp.Intersect, unify(right))

  def union(left: Query | String)(right: Query | String): Query =
    Binary(unify(left), BinOp.Union, unify(right))

  def except(left: Query | String)(right: Query | String): Query =
    Binary(unify(left), BinOp.Except, unify(right))

  def set(qs: List[Query | String]): Query =
    Set(qs.map(unify))

def renderOp(op: BinOp): String = op match
  case BinOp.Intersect => "intersect"
  case BinOp.Union     => "union"
  case BinOp.Except    => "except"

def renderQuery(q: Query): String = {
  import Query._

  def go(q: Query): Eval[String] = {
    def fns(name: String, qs: List[Query], sep: String = ", "): Eval[String] = Eval.defer:
      qs.traverse(go).map { args => s"${name}(${args.mkString(sep)})" }

    def fn(name: String, qs: Query*): Eval[String] = fns(name, qs.toList)
    q match
      case Word(value)                => Eval.now(s"\"${value}\"")
      case Integer(value)             => Eval.now(value.toString)
      case Var(name)                  => Eval.now(s"$$${name}")
      case Deps(q, depth)             => fns("deps", q :: depth.toList)
      case RDeps(u, x, depth)         => fns("rdeps", u :: x :: depth.toList)
      case AllRDeps(u, depth)         => fns("allrdeps", u :: depth.toList)
      case SamePkgDirectRDeps(u)      => fn("samepkgdirectrdeps", u)
      case Siblings(u)                => fn("siblings", u)
      case Some(q, count)             => fns("some", q :: count.toList)
      case SomePaths(s, e)            => fn("somepaths", s, e)
      case AllPaths(s, e)             => fn("allpaths", s, e)
      case Kind(w, input)             => fn("kind", w, input)
      case Filter(w, input)           => fn("filter", w, input)
      case Attr(name, pattern, input) => fn("attr", name, pattern, input)
      case Visible(predicate, input)  => fn("visible", predicate, input)
      case Labels(attrName, input)    => fn("labels", attrName, input)
      case Tests(input)               => fn("tests", input)
      case Buildfiles(input)          => fn("buildfiles", input)
      case RBuildfiles(ws)            => fn("rbuildfiles", ws.toList: _*)
      case Loadfiles(input)           => fn("loadfiles", input)
      case Let(name, value, in)       => (go(value), go(in)).mapN((v, i) => s"let $name = ${v} in ${i}")
      case Parens(q)                  => go(q).map(s => s"(${s})")
      case Binary(left, op, right)    => (go(left), go(right)).mapN((l, r) => s"${l} ${renderOp(op)} ${r}")
      case Set(qs)                    => fns("set", qs, sep = " ")
      case Inputs(w, q)               => fn("inputs", w, q)
      case Outputs(w, q)              => fn("outputs", w, q)
      case Mnemonic(w, q)             => fn("mnemonic", w, q)
  }

  go(q).value
}
