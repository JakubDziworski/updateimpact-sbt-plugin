package com.updateimpact

import java.io.FileInputStream
import java.util.Properties

import com.updateimpact.report.DependencyId
import org.apache.ivy.Ivy
import org.apache.ivy.core.LogOptions
import org.apache.ivy.core.module.descriptor.DependencyDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorParser

class FindDependencyDescriptors(ivy: Ivy) {
  def forDependencyId(di: DependencyId): Option[Seq[DependencyDescriptor]] = {
    val mrid = ModuleRevisionId.newInstance(di.getGroupId, di.getArtifactId, di.getVersion)
    findModuleFromCache(mrid)
      .orElse(findModuleUsingResolveEngine(mrid))
  }

  private def findModuleFromCache(mrid: ModuleRevisionId) = {
    val ivyFile = ivy.getResolutionCacheManager.getResolvedIvyFileInCache(mrid)
    if (!ivyFile.exists()) {
      None
    } else {
      // ResolutionCache:45
      val desc = XmlModuleDescriptorParser.getInstance().parseDescriptor(ivy.getSettings, ivyFile.toURI.toURL, false)
      val revReplacements = resolvedRevisionsFromCachedProperties(mrid)

      val result = desc.getDependencies.map { d =>
        revReplacements.get(d.getDependencyRevisionId) match {
          case None => d
          case Some(rev) => d.clone(ModuleRevisionId.newInstance(d.getDependencyRevisionId, rev))
        }
      }

      Some(result.toSeq)
    }
  }

  // DeliverEngine:117
  private def resolvedRevisionsFromCachedProperties(mrid: ModuleRevisionId): Map[ModuleRevisionId, String] = {
    var resolvedRevisions = Map[ModuleRevisionId, String]() // Map (ModuleId -> String revision)
    val ivyProperties = ivy.getResolutionCacheManager.getResolvedIvyPropertiesInCache(mrid)
    if (!ivyProperties.exists()) {
      Map()
    } else {
      val props = new Properties()
      val in = new FileInputStream(ivyProperties)
      try props.load(in) finally in.close()

      val iter = props.keySet().iterator()
      while (iter.hasNext) {
        val depMridStr = iter.next().asInstanceOf[String]
        val parts = props.getProperty(depMridStr).split(" ")
        val decodedMrid = ModuleRevisionId.decode(depMridStr)

        if (parts.length >= 3) {
          resolvedRevisions += decodedMrid -> parts(2)
        } else {
          resolvedRevisions += decodedMrid -> parts(0)
        }
      }

      resolvedRevisions
    }
  }

  private def findModuleUsingResolveEngine(mrid: ModuleRevisionId) = {
    val resolveOptions = new ResolveOptions
    resolveOptions.setResolveId(ResolveOptions.getDefaultResolveId(mrid.getModuleId))
    resolveOptions.setLog(LogOptions.LOG_QUIET)
    resolveOptions.setDownload(false)
    resolveOptions.setRefresh(false)
    resolveOptions.setCheckIfChanged(false)
    resolveOptions.setOutputReport(false)
    resolveOptions.setUseCacheOnly(true)
    resolveOptions.setValidate(false)

    Option(ivy.getResolveEngine.findModule(mrid, resolveOptions)).map(_.getDescriptor.getDependencies.toSeq)
  }
}
