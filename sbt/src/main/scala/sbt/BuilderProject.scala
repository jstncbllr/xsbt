/* sbt -- Simple Build Tool
 * Copyright 2008, 2009  Mark Harrah, David MacIver
 */
package sbt

import BasicProjectPaths._

sealed abstract class InternalProject extends Project
{
	override def defaultLoggingLevel = Level.Warn
	override final def historyPath = None
	override def tasks: Map[String, Task] = Map.empty
	override final protected def disableCrossPaths = false
	override final def shouldCheckOutputDirectories = false
}
private sealed abstract class BasicBuilderProject extends InternalProject
{
	def sourceFilter = "*.scala" | "*.java"
	def jarFilter: NameFilter = "*.jar"
	def compilePath = outputPath / DefaultMainCompileDirectoryName
	def mainResourcesPath = path(DefaultResourcesDirectoryName)
	def dependencyPath = path(DefaultDependencyDirectoryName)
	def libraries = descendents(dependencyPath, jarFilter)
	override final def dependencies = Nil

	protected final def logInfo(messages: String*): Unit = atInfo { messages.foreach(message => log.info(message)) }
	protected final def atInfo(action: => Unit)
	{
		val oldLevel = log.getLevel
		log.setLevel(Level.Info)
		action
		log.setLevel(oldLevel)
	}

	def projectClasspath = compilePath +++ libraries +++ sbtJars
	def sbtJars = info.sbtClasspath

	abstract class BuilderCompileConfiguration extends AbstractCompileConfiguration
	{
		def projectPath = info.projectPath
		def log = BasicBuilderProject.this.log
		def options = CompileOptions.Deprecation :: CompileOptions.Unchecked :: Nil
		def javaOptions = Nil
		def maxErrors = ScalaProject.DefaultMaximumCompileErrors
		def compileOrder = CompileOrder.Mixed
	}
	def definitionCompileConfiguration =
		new BuilderCompileConfiguration
		{
			def label = "builder"
			def sourceRoots = info.projectPath +++ path(DefaultSourceDirectoryName)
			def sources = (info.projectPath * sourceFilter) +++ path(DefaultSourceDirectoryName).descendentsExcept(sourceFilter, defaultExcludes)
			def outputDirectory = compilePath
			def classpath = projectClasspath
			def analysisPath = outputPath / DefaultMainAnalysisDirectoryName
		}

	def tpe: String

		import xsbt.ScalaInstance

	lazy val definitionCompileConditional = new BuilderCompileConditional(definitionCompileConfiguration, buildCompiler, tpe)
	final class BuilderCompileConditional(config: BuilderCompileConfiguration, compiler: xsbt.AnalyzingCompiler, tpe: String) extends AbstractCompileConditional(config, compiler)
	{
		type AnalysisType = BuilderCompileAnalysis
		override protected def constructAnalysis(analysisPath: Path, projectPath: Path, log: Logger) =
			new BuilderCompileAnalysis(analysisPath, projectPath, log)
		override protected def execute(cAnalysis: ConditionalAnalysis): Option[String] =
		{
			if(cAnalysis.dirtySources.isEmpty)
				None
			else
			{
				definitionChanged()
				logInfo(
					"Recompiling " + tpe + "...",
					 "\t" + cAnalysis.toString)
				super.execute(cAnalysis)
			}
		}
		protected def analysisCallback: AnalysisCallback =
			new BasicAnalysisCallback(info.projectPath, analysis)
			{
				def superclassNames = List(Project.ProjectClassName)
				def annotationNames = Nil
				def foundApplication(sourcePath: Path, className: String)  {}
				def foundAnnotated(sourcePath: Path, subclassName: String, annotationName: String, isModule: Boolean) {}
				def foundSubclass(sourcePath: Path, subclassName: String, superclassName: String, isModule: Boolean)
				{
					if(superclassName == Project.ProjectClassName && !isModule)
					{
						log.debug("Found " + tpe + " " + subclassName)
						analysis.addProjectDefinition(sourcePath, subclassName)
					}
				}
			}
	}
	protected def definitionChanged() {}
	lazy val compile = compileTask
	def compileTask = task { definitionCompileConditional.run }

	def projectDefinition: Either[String, Option[String]] =
	{
		definitionCompileConditional.analysis.allProjects.toList match
		{
			case Nil =>
				log.debug("No " + tpe + "s detected using default project.")
				Right(None)
			case singleDefinition :: Nil => Right(Some(singleDefinition))
			case multipleDefinitions =>Left(multipleDefinitions.mkString("Multiple " + tpe + "s detected: \n\t","\n\t","\n"))
		}
	}
	override final def methods = Map.empty
}
/** The project definition used to build project definitions. */
private final class BuilderProject(val info: ProjectInfo, val pluginPath: Path, rawLogger: Logger) extends BasicBuilderProject
{
	lazy val pluginProject =
	{
		if(pluginPath.exists)
			Some(new PluginBuilderProject(ProjectInfo(pluginPath.asFile, Nil, None)(rawLogger, info.app, info.buildScalaVersion)))
		else
			None
	}
	override def projectClasspath = super.projectClasspath +++
		pluginProject.map(_.pluginClasspath).getOrElse(Path.emptyPathFinder)
	def tpe = "project definition"

	override def compileTask = super.compileTask dependsOn(pluginProject.map(_.syncPlugins).toList : _*)

	final class PluginBuilderProject(val info: ProjectInfo) extends BasicBuilderProject
	{
		lazy val pluginUptodate = propertyOptional[Boolean](false)
		def tpe = "plugin definition"
		def managedSourcePath = path(BasicDependencyPaths.DefaultManagedSourceDirectoryName)
		def managedDependencyPath = crossPath(BasicDependencyPaths.DefaultManagedDirectoryName)
		override protected def definitionChanged() { setUptodate(false) }
		private def setUptodate(flag: Boolean)
		{
			pluginUptodate() = flag
			saveEnvironment()
		}

		private def pluginTask(f: => Option[String]) = task { if(!pluginUptodate.value) f else None }

		lazy val syncPlugins = pluginTask(sync()) dependsOn(extractSources)
		lazy val extractSources = pluginTask(extract()) dependsOn(update)
		lazy val update = pluginTask(loadAndUpdate()) dependsOn(compile)

		private def sync() = pluginCompileConditional.run orElse { setUptodate(true); None }
		private def extract() =
		{
			FileUtilities.clean(managedSourcePath, log) orElse
			Control.lazyFold(plugins.get.toList) { jar =>
				Control.thread(FileUtilities.unzip(jar, extractTo(jar), sourceFilter, log)) { extracted =>
					if(!extracted.isEmpty)
						logInfo("\tExtracted source plugin " + jar + " ...")
					None
				}
			}
		}
		private def loadAndUpdate() =
		{
			Control.thread(projectDefinition) {
				case Some(definition) =>
					logInfo("\nUpdating plugins...")
					val pluginInfo = ProjectInfo(info.projectPath.asFile, Nil, None)(rawLogger, info.app, info.buildScalaVersion)
					val pluginBuilder = Project.constructProject(pluginInfo, Project.getProjectClass[PluginDefinition](definition, projectClasspath, getClass.getClassLoader))
					pluginBuilder.projectName() = "Plugin builder"
					pluginBuilder.projectVersion() = OpaqueVersion("1.0")
					val result = pluginBuilder.update.run
					if(result.isEmpty)
					{
						atInfo {
							log.success("Plugins updated successfully.")
							log.info("")
						}
					}
					result
				case None => None
			}
		}
		def extractTo(jar: Path) =
		{
			val name = jar.asFile.getName
			managedSourcePath / name.substring(0, name.length - ".jar".length)
		}
		def plugins = descendents(managedDependencyPath, jarFilter)
		def pluginClasspath: PathFinder = plugins +++ pluginCompileConfiguration.outputDirectory

		lazy val pluginCompileConditional = new BuilderCompileConditional(pluginCompileConfiguration, buildCompiler, "plugin")
		lazy val pluginCompileConfiguration =
			new BuilderCompileConfiguration
			{
				def label = "plugin builder"
				def sourceRoots = managedSourcePath
				def sources = descendents(sourceRoots, sourceFilter)
				def outputDirectory = outputPath / "plugin-classes"
				def classpath: PathFinder = pluginClasspath +++ sbtJars
				def analysisPath = outputPath / "plugin-analysis"
			}
	}
}
class PluginDefinition(val info: ProjectInfo) extends InternalProject with BasicManagedProject
{
	override def defaultLoggingLevel = Level.Info
	override final def outputPattern = "[artifact](-[revision]).[ext]"
	override final val tasks = Map("update" -> update)
	override def projectClasspath(config: Configuration) = Path.emptyPathFinder
	override def dependencies = info.dependencies
}
class PluginProject(info: ProjectInfo) extends DefaultProject(info)
{
	/* Since plugins are distributed as source, there is no need to append _<scala.version>  */
	override def moduleID = normalizedName
	/* Fix the version used to build to the version currently running sbt. */
	override def buildScalaVersion = defScalaVersion.value
	/* Add sbt to the classpath */
	override def unmanagedClasspath = super.unmanagedClasspath +++ info.sbtClasspath
	/* Package the plugin as source. */
	override def packageAction = packageSrc dependsOn(test)
	override def packageSrcJar = jarPath
	/* Some setup to make publishing quicker to configure. */
	override def useMavenConfigurations = true
	override def managedStyle = ManagedStyle.Maven
}
class ProcessorProject(info: ProjectInfo) extends DefaultProject(info)
{
	/* Fix the version used to build to the version currently running sbt. */
	override def buildScalaVersion = defScalaVersion.value
	/* Add sbt to the classpath */
	override def unmanagedClasspath = super.unmanagedClasspath +++ info.sbtClasspath
	/* Some setup to make publishing quicker to configure. */
	override def useMavenConfigurations = true
	override def managedStyle = ManagedStyle.Maven
}