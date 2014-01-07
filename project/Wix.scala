import sbt._
import sbt.Keys._

import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.packager.Keys._

import com.typesafe.sbt.packager.windows._
import WixHelper.{generateComponentsAndDirectoryXml, cleanFileName}

// can't call it Windows, that's a config name
object Wix {
  // Windows installer configuration
  def settings: Seq[Setting[_]] = Seq(
    mappings in Windows := (mappings in Universal).value,
    // distributionFiles in Windows += (packageMsi in Windows).value,

    wixProductId        := "7606e6da-e168-42b5-8345-b08bf774cb30",
    wixProductUpgradeId := "6061c134-67c7-4fb2-aff5-32b01a186968",
    // wixProductComments  := "Scala Programming language for use in Windows.",
    version in Windows  := makeWindowsVersion(version.value),

    wixProductConfig := makeProductConfig((stagingDirectory in Universal).value, (stagingDirectory in UniversalDocs).value),
    wixProductConfig <<= (wixProductConfig
      dependsOn (stage in Universal)
      dependsOn (stage in UniversalDocs))
  )

  private def makeProductConfig(stage: File, stageApi: File) = {
    val (bin, binDirXml0) = generateComponentsAndDirectoryXml(stage / "bin")
    val (doc, docDirXml)  = generateComponentsAndDirectoryXml(stage / "doc", "doc_")
    val (lib, libDirXml)  = generateComponentsAndDirectoryXml(stage / "lib")
    val (api, apiDirXml)  = generateComponentsAndDirectoryXml(stageApi / "api", "api_")

    // add component that adds bin folder to path
    val binDirXml = binDirXml0 match {
      case d@(<Directory>{files@_*}</Directory>) =>
        <Directory Id={d \ "@Id"} Name={d \ "@Name"}>
          {files}
          <Component Id="ScalaBinPath" Guid="244b8829-bd74-40ff-8c1d-5717be94538d">
            <CreateFolder/>
            <Environment Id="PATH" Name="PATH" Value="[INSTALLDIR]\bin" Permanent="no" Part="last" Action="set" System="yes"/>
          </Component>
        </Directory>
    }

    val directoriesXml = binDirXml ++ libDirXml ++ docDirXml ++ apiDirXml

    def componentRefs(refs: Seq[String]) = refs map {ref => <ComponentRef Id={ref}/>}
    val core = (bin ++ lib ++ doc)

    val apiDirId    = apiDirXml \ "@Id"
    val apiIndex    = s"[$apiDirId]/scala-library/index.html"
    // TODO: create (advertised?) shortcut to other api subdirs and to repl -- man, why is this so hard?
    // val scalaRepl   = """[INSTALLDIR]\bin\scala.bat"""
    val licensePath = cleanFileName((stage / "doc" / "License.rtf").getAbsolutePath)

    <xml:group>
      <Directory Id="TARGETDIR" Name="SourceDir">
        <Directory Id="ProgramMenuFolder">
          <Directory Id="ApplicationProgramsFolder" Name="scala"/>
        </Directory>
        <Directory Id="ProgramFilesFolder" Name="PFiles">
          <Directory Id="INSTALLDIR" Name="scala">
            { directoriesXml }
          </Directory>
        </Directory>
      </Directory>

      <DirectoryRef Id="ApplicationProgramsFolder">
        <Component Id="ApiShortcut" Guid="1607077c-58ca-4b4a-ac82-277a83b9360a">
          <Shortcut Id="ApplicationStartMenuShortcut" Name="Scala API Documentation" Description="Scala library API documentation (web)" Target={apiIndex}/>
          <RemoveFolder Id="ApplicationProgramsFolder" On="uninstall"/>
          <RegistryValue Root="HKCU" Key="Software\Microsoft\scala" Name="installed" Type="integer" Value="1" KeyPath="yes"/>
        </Component>
      </DirectoryRef>

      <Feature Id="Complete" Title="The Scala Programming Language" Description="The Windows installation of the Scala Programming Language" Display="expand" Level="1" ConfigurableDirectory="INSTALLDIR">
        <Feature Id="lang" Title="The core scala language." Level="1" Absent="disallow">
         { componentRefs(core) }
        </Feature>

        <Feature Id="ScalaPathF" Title="Update system PATH" Description="Add the Scala binaries (scala, scalac, scaladoc, scalap) to your system path." Level="1">
          <ComponentRef Id="ScalaBinPath"/>
        </Feature>

        <Feature Id="fdocs" Title="Documentation for the Scala library" Description="Install the Scala documentation." Level="1">
          { componentRefs(api) }
          <Feature Id="fapilink" Title="Start Menu link" Description="Menu shortcut to Scala API documentation." Level="1">
            <ComponentRef Id="ApiShortcut"/>
          </Feature>
        </Feature>
      </Feature>

      <MajorUpgrade AllowDowngrades="no" Schedule="afterInstallInitialize" DowngradeErrorMessage="A later version of [ProductName] is already installed.  Setup will now exit."/>

      <UIRef Id="WixUI_FeatureTree"/>
      <UIRef Id="WixUI_ErrorProgressText"/>
      <Property Id="WIXUI_INSTALLDIR" Value="INSTALLDIR"/>
      <WixVariable Id="WixUILicenseRtf" Value={ licensePath }/>
    </xml:group>
  }

  // This is a complicated means to convert maven version numbers into monotonically increasing windows versions.
  private def makeWindowsVersion(version: String): String = {
    val Majors = new scala.util.matching.Regex("(\\d+).(\\d+).(\\d+)(-.*)?")
    val Rcs = new scala.util.matching.Regex("(\\-\\d+)?\\-RC(\\d+)")
    val Milestones = new scala.util.matching.Regex("(\\-\\d+)?\\-M(\\d+)")
    val BuildNum = new scala.util.matching.Regex("\\-(\\d+)")

    def calculateNumberFour(buildNum: Int = 0, rc: Int = 0, milestone: Int = 0) =
      if(rc > 0 || milestone > 0) (buildNum)*400 + rc*20  + milestone
      else (buildNum+1)*400 + rc*20  + milestone

    version match {
      case Majors(major, minor, bugfix, rest) => Option(rest) getOrElse "" match {
        case Milestones(null, num)            => major + "." + minor + "." + bugfix + "." + calculateNumberFour(0,0,num.toInt)
        case Milestones(bnum, num)            => major + "." + minor + "." + bugfix + "." + calculateNumberFour(bnum.drop(1).toInt,0,num.toInt)
        case Rcs(null, num)                   => major + "." + minor + "." + bugfix + "." + calculateNumberFour(0,num.toInt,0)
        case Rcs(bnum, num)                   => major + "." + minor + "." + bugfix + "." + calculateNumberFour(bnum.drop(1).toInt,num.toInt,0)
        case BuildNum(bnum)                   => major + "." + minor + "." + bugfix + "." + calculateNumberFour(bnum.toInt,0,0)
        case _                                => major + "." + minor + "." + bugfix + "." + calculateNumberFour(0,0,0)
      }
      case x => x
    }
  }
}
