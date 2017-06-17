package org.scalafmt.config

import scala.collection.immutable.Seq
import scala.collection.immutable.Set

import metaconfig.Configured._
import metaconfig._
import org.scalafmt.util.LoggerOps

trait Settings {

  val indentOperatorsIncludeAkka = "^.*=$"
  val indentOperatorsExcludeAkka = "^$"
  val indentOperatorsIncludeDefault = ".*"
  val indentOperatorsExcludeDefault = "^(&&|\\|\\|)$"

  val default = ScalafmtConfig()

  val intellij: ScalafmtConfig = default.copy(
    continuationIndent = ContinuationIndent(2, 2),
    align = default.align.copy(openParenCallSite = false),
    optIn = default.optIn.copy(
      configStyleArguments = false
    ),
    danglingParentheses = true
  )

  def addAlign(style: ScalafmtConfig): ScalafmtConfig = style.copy(
    align = style.align.copy(
      tokens = AlignToken.default
    )
  )

  val defaultWithAlign: ScalafmtConfig = addAlign(default)

  val default40: ScalafmtConfig = default.copy(maxColumn = 40)
  val default120: ScalafmtConfig = default.copy(maxColumn = 120)

  /**
    * Experimental implementation of:
    * https://github.com/scala-js/scala-js/blob/master/CODINGSTYLE.md
    */
  val scalaJs: ScalafmtConfig = default.copy(
    binPack = BinPack(
      unsafeDefnSite = true,
      unsafeCallSite = true,
      parentConstructors = true
    ),
    continuationIndent = ContinuationIndent(4, 4),
    importSelectors = ImportSelectors.binPack,
    newlines = default.newlines.copy(
      neverInResultType = true,
      neverBeforeJsNative = true,
      sometimesBeforeColonInMethodReturnType = false
    ),
    // For some reason, the bin packing does not play nicely with forced
    // config style. It's fixable, but I don't want to spend time on it
    // right now.
    runner = conservativeRunner,
    docstrings = Docstrings.JavaDoc,
    align = default.align.copy(
      arrowEnumeratorGenerator = false,
      tokens = Set(AlignToken.caseArrow),
      ifWhileOpenParen = false
    )
  )

  /**
    * Ready styles provided by scalafmt.
    */
  val activeStyles: Map[String, ScalafmtConfig] =
    Map(
      "Scala.js" -> scalaJs,
      "IntelliJ" -> intellij
    ) ++ LoggerOps.name2style(
      default,
      defaultWithAlign
    )

  val availableStyles: Map[String, ScalafmtConfig] = {
    activeStyles ++ LoggerOps.name2style(
      scalaJs
    )
  }.map { case (k, v) => k.toLowerCase -> v }

  def conservativeRunner: ScalafmtRunner = default.runner.copy(
    optimizer = default.runner.optimizer.copy(
      // The tests were not written in this style
      forceConfigStyleOnOffset = 500,
      forceConfigStyleMinArgCount = 5
    )
  )

  // TODO(olafur) move these elsewhere.
  val testing = default.copy(
    maxColumn = 79,
    assumeStandardLibraryStripMargin = false,
    includeCurlyBraceInSelectChains = false,
    align = default.align.copy(tokens = Set.empty),
    optIn = default.optIn.copy(
      breakChainOnFirstMethodDot = false
    ),
    // The new agressive config style breaks ~40 unit tests. The diff output
    // looks nice, but updating the unit tests would take too much time.
    // I can imagine that I will throw away most of the tests and replace them
    // with autogenerated tests from scala-repos.
    runner = conservativeRunner
  )
  val unitTest80 = testing.copy(
    continuationIndent = ContinuationIndent(4, 4)
  )

  val unitTest40 = unitTest80.copy(maxColumn = 39)

  def oneOf[T](m: Map[String, T])(input: String): Configured[T] =
    m.get(input.toLowerCase()) match {
      case Some(x) => Ok(x)
      case None =>
        val available = m.keys.mkString(", ")
        val msg =
          s"Unknown line endings type $input. Expected one of $available"
        ConfError.msg(msg).notOk

    }

  def configReader(baseReader: ScalafmtConfig): ConfDecoder[ScalafmtConfig] =
    ConfDecoder.instance[ScalafmtConfig] {
      case conf @ Conf.Obj(values) =>
        val map = values.toMap
        map.get("style") match {
          case Some(Conf.Str(baseStyle)) =>
            val noStyle = Conf.Obj(values.filterNot(_._1 == "style"))
            ScalafmtConfig.availableStyles.get(baseStyle.toLowerCase) match {
              case Some(s) => s.reader.read(noStyle)
              case None =>
                val alternatives =
                  ScalafmtConfig.activeStyles.keys.mkString(", ")
                ConfError
                  .msg(
                    s"Unknown style name $baseStyle. Expected one of: $alternatives")
                  .notOk
            }
          case _ => baseReader.reader.read(conf)
        }
    }

  def gimmeStrPairs(tokens: Seq[String]): Seq[(String, String)] = {
    tokens.map { token =>
      val splitted = token.split(";", 2)
      if (splitted.length != 2)
        throw new IllegalArgumentException("pair must contain ;")
      (splitted(0), splitted(1))
    }
  }
  def alignReader(base: ConfDecoder[Align]): ConfDecoder[Align] =
    ConfDecoder.instance[Align] {
      case Conf.Str("none") | Conf.Bool(false) => Ok(Align.none)
      case Conf.Str("some" | "default")        => Ok(Align.some)
      case Conf.Str("more") | Conf.Bool(true)  => Ok(Align.more)
      case Conf.Str("most")                    => Ok(Align.most)
      case els                                 => base.read(els)
    }
  def alignTokenReader(
      initTokens: Set[AlignToken]): ConfDecoder[Set[AlignToken]] = {
    val baseReader = implicitly[ConfDecoder[Set[AlignToken]]]
    ConfDecoder.instance[Set[AlignToken]] {
      case Conf.Obj(("add", conf) :: Nil) =>
        baseReader.read(conf).map(initTokens ++ _)
      case els => baseReader.read(els)
    }
  }
}
