package xsbt

import java.io.File

import org.eclipse.jgit.api.{ CloneCommand, Git }
import sbt.internal.util.ConsoleLogger
import sbt.io.{ IO, RichFile }
import xsbt.ZincBenchmark.CompilationInfo
import xsbti._
import xsbti.compile.SingleOutput

import scala.util.Try

case class ProjectSetup(at: File, compilationInfo: CompilationInfo, run: ZincBenchmark.Run) {
  def compile(): Unit = run.compile(compilationInfo.sources)
}

case class BenchmarkOptions(classpath: String, args: Array[String])

case class ZincSetup(result: Either[Throwable, List[ProjectSetup]]) {
  private def crash(throwable: Throwable) = {
    val message =
      s"""Unexpected error when setting up Zinc benchmarks:
        |$throwable
      """.stripMargin
    sys.error(message)
  }

  /** Crash at this point because JMH wants the list of setup runs. */
  def getOrCrash: List[ProjectSetup] =
    result.fold(crash, identity)
}

/* Classes are defined `private[xsbt]` to avoid scoping issues w/ `Compiler0`. */

/** Instantiate a `ZincBenchmark` from a given project. */
private[xsbt] class ZincBenchmark(toCompile: BenchmarkProject) {
  private val UseJavaCpArg = Array("-usejavacp")
  def prepare: ZincSetup = ZincSetup {
    toCompile.cloneRepo.flatMap { rootDir =>
      toCompile.getClasspathAndSources(rootDir).map { buildInfos =>
        buildInfos.map { buildInfo0 =>
          val javaFile = rootDir.asFile
          val buildInfo = {
            if (!toCompile.useJavaCp) buildInfo0
            else {
              val currentOpts = buildInfo0.scalacOptions
              buildInfo0.copy(scalacOptions = currentOpts ++ UseJavaCpArg)
            }
          }
          // Set up the compiler and store the current setup
          val run = ZincBenchmark.setUpCompiler(buildInfo, javaFile)
          ProjectSetup(javaFile, buildInfo, run)
        }
      }
    }
  }
}

private[xsbt] object ZincBenchmark {
  type Sources = List[String]
  type Run = CachedCompiler0#Compiler#Run

  /** Set up the compiler to compile `sources` with -cp `classpath` at `targetDir`. */
  def setUpCompiler(compilationInfo: CompilationInfo, targetDir: File): Run = {
    // Name hashing is true by default
    val callback: AnalysisCallback = new xsbti.TestCallback(true)
    val compiler = prepareCompiler(targetDir, callback, compilationInfo)
    new compiler.Run
  }

  /* ***************************************************** */
  /* Copied over from `ScalaCompilerForUnitTesting.scala`  */
  /* ***************************************************** */

  def prepareCompiler(
    outputDir: File,
    analysisCallback: AnalysisCallback,
    compilationInfo: CompilationInfo
  ): CachedCompiler0#Compiler = {
    object output extends SingleOutput {
      def outputDirectory: File = outputDir
      override def toString = s"SingleOutput($outputDirectory)"
    }
    val args = compilationInfo.scalacOptions
    val classpath = compilationInfo.classpath
    val weakLog = new WeakLog(ConsoleLogger(), ConsoleReporter)
    val cachedCompiler = new CachedCompiler0(args, output, weakLog, false)
    val settings = cachedCompiler.settings
    settings.classpath.value = classpath
    val delegatingReporter = DelegatingReporter(settings, ConsoleReporter)
    val compiler = cachedCompiler.compiler
    compiler.set(analysisCallback, delegatingReporter)
    compiler
  }

  private object ConsoleReporter extends Reporter {
    def reset(): Unit = ()
    def hasErrors: Boolean = false
    def hasWarnings: Boolean = false
    def printWarnings(): Unit = ()
    def problems: Array[Problem] = Array.empty
    def log(pos: Position, msg: String, sev: Severity): Unit = println(msg)
    def comment(pos: Position, msg: String): Unit = ()
    def printSummary(): Unit = ()
  }

  /* ************************************************************* */
  /* Utils to programmatically instantiate Compiler from sbt setup  */
  /* ************************************************************* */

  object Git {

    /** Clone a git repository using JGit. */
    def clone(repo: String, at: File): Either[Throwable, Git] = {
      val cloneCommand =
        new CloneCommand().setURI(s"https://github.com/$repo").setDirectory(at)
      Try(cloneCommand.call()).toEither
    }

    /** Checkout a hash in a concrete repository and throw away Ref. */
    def checkout(git: Git, hash: String): Either[Throwable, Git] =
      Try(git.checkout().setName(hash).call()).toEither.map(_ => git)
  }

  /** Sbt classpath, scalac options and sources for a given subproject. */
  case class CompilationInfo(
    classpath: String,
    sources: List[String],
    scalacOptions: Array[String]
  )

  /** Helper to get the classpath and the sources of a given sbt subproject. */
  object CompilationInfo {

    /** Generate class from output generated by `generateImpl`. */
    def apply(
      classpath: String,
      sources: String,
      options: String
    ): CompilationInfo = {
      val sourcesL = sources.split(" ").toList
      val optionsL = options.split(" ")
      CompilationInfo(classpath, sourcesL, optionsL)
    }

    private val TaskName = "getAllSourcesAndClasspath"
    private val ExpectedFileType = "out"

    private def generateTaskName(sbtProject: String) =
      s"$TaskName-$sbtProject"

    def generateOutputFile(sbtProject: String) =
      s"${generateTaskName(sbtProject)}.$ExpectedFileType"

    /** Generate an implementation for the task targeted at `sbtProject`. */
    def generateImpl(sbtProject: String, outputFile: File): String = {
      val taskName = generateTaskName(sbtProject)
      s"""
         |lazy val `$taskName` =
         |  taskKey[Unit]("Get source files and classpath of subprojects")
         |`$taskName` in ThisBuild := {
         |  val file = new File("${outputFile.getAbsolutePath}")
         |  val rawSources = (sources in Compile in $sbtProject).value
         |  val sourcesLine = rawSources.map(_.getAbsolutePath).mkString(" ")
         |  val rawClasspath = (dependencyClasspath in Compile in $sbtProject).value
         |  val classpathLine = rawClasspath.map(_.data.getAbsolutePath).mkString(":")
         |  val optionsLine = (scalacOptions in Compile in $sbtProject).value.mkString(" ")
         |  IO.writeLines(file, Seq(sourcesLine, classpathLine, optionsLine))
         |}
      """.stripMargin
    }

    /** Get sbt task command that has to be run along with sbt. */
    def getClasspathAndSources(
      sbtProject: String,
      atDir: File,
      outputFile: File
    ): Either[Throwable, CompilationInfo] = {

      import scala.sys.process._
      val taskName = generateTaskName(sbtProject)
      val sbt = Try(Process(s"sbt ++2.12.1 $taskName", atDir).!).toEither

      sbt.flatMap { _ =>
        // Sbt succeeded, parse the output file
        val contents = IO.read(outputFile)
        val lines = contents.split("\n")
        lines match {
          case Array(sourcesL, classpathL, optionsL) =>
            Right(
              CompilationInfo(classpathL.trim, sourcesL.trim, optionsL.trim)
            )
          case _ => Left(new Exception("Error when reading classpath output."))
        }
      }
    }
  }

  /** Represent the build information. */
  type BuildInfo = Either[Throwable, List[CompilationInfo]]
}

/** Represent a project on which to run benchmarks. */
case class BenchmarkProject(
  repo: String,
  hash: String,
  subprojects: Seq[String],
  useJavaCp: Boolean = true
) {
  import ZincBenchmark.{ Git, CompilationInfo, BuildInfo }

  def cloneRepo: Either[Throwable, RichFile] = {
    val tempDir = sbt.io.IO.createTemporaryDirectory
    val gitClient = Git.clone(repo, tempDir)
    gitClient
      .flatMap(Git.checkout(_, hash))
      .map(_ => new RichFile(tempDir))
  }

  def getClasspathAndSources(at: RichFile): BuildInfo = {
    def getClasspathAndSources(
      subproject: String
    ): Either[Throwable, CompilationInfo] = {
      val filename = CompilationInfo.generateOutputFile(subproject)
      val outputFile = at / filename
      val taskImpl = CompilationInfo.generateImpl(subproject, outputFile)
      // TODO: Remove assumption of build.sbt?
      val buildFile = at / "build.sbt"
      val appendFile = Try(IO.append(buildFile, taskImpl)).toEither
      appendFile.flatMap { _ =>
        CompilationInfo.getClasspathAndSources(
          subproject,
          at.asFile,
          outputFile
        )
      }
    }

    val init: BuildInfo = Right(Nil)
    subprojects.foldLeft(init) { (result, subproject) =>
      result.flatMap { acc =>
        getClasspathAndSources(subproject).map(_ :: acc)
      }
    }
  }
}
