package com.ofg.uptodate.finder.maven

import com.ofg.uptodate.UptodatePluginExtension
import com.ofg.uptodate.dependency.Dependency
import com.ofg.uptodate.dependency.Version
import com.ofg.uptodate.dependency.VersionPatternMatcher
import com.ofg.uptodate.finder.FinderConfiguration
import com.ofg.uptodate.finder.NewVersionFinder
import com.ofg.uptodate.finder.RepositorySettings
import groovy.util.logging.Slf4j
import groovy.util.slurpersupport.NodeChild

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

@Slf4j
class LocalMavenNewVersionFinderFactory { // does not: implements NewVersionFinderFactory {

    @Override
    NewVersionFinder create(String repoUrl, UptodatePluginExtension uptodatePluginExtension, List<Dependency> dependencies) {
        FinderConfiguration finderConfiguration = new FinderConfiguration(
                new RepositorySettings(repoUrl: repoUrl, ignoreRepo: false),
                uptodatePluginExtension,
                dependencies.size())
        return new NewVersionFinder(
                LocalMavenRepoLatestVersionsCollector(repoUrl, uptodatePluginExtension),
                finderConfiguration)
    }

    private Closure<Future> LocalMavenRepoLatestVersionsCollector(String repoUrl, UptodatePluginExtension uptodatePluginExtension) {
        return getLatestFromLocalMavenRepo.curry(repoUrl, uptodatePluginExtension)
    }

    private Closure<Future> getLatestFromLocalMavenRepo = { String repoUrl, UptodatePluginExtension uptodatePluginExtension, Dependency dependency ->
        Closure cl = { ->
            if (!repoUrl.endsWith('/')) {
                repoUrl = repoUrl.concat('/')
            }
            String newUrl = "${repoUrl}${dependency.group.split('\\.').join('/')}/${dependency.name}/maven-metadata.xml"
            File file = new File(new URI(newUrl))
            if (file.exists()) {
                log.debug("local repo $repoUrl has metadata.xml")
                // if maven-metadata.xml exists, use that
                def xml = new XmlSlurper().parse(file)
                try {
                    return [dependency, new Dependency(dependency, getLatestDependencyVersion(xml.versioning.release.text(), xml, uptodatePluginExtension.excludedVersionPatterns))]
                } catch (Exception e) {
                    log.error("Exception occurred while trying to fetch latest dependencies. The fetched XML is [$xml]".toString(), e)
                }
            }
            // else check for version-subdirectories (f.e. mavenLocal() has no maven-metadata.xml ?)
            newUrl = "${repoUrl}${dependency.group.split('\\.').join('/')}/${dependency.name}"
            file = new File(new URI(newUrl))
            if (file.exists()) {
                log.debug("fallback to version-directories for $repoUrl")
                return [dependency, new Dependency(dependency, getLatestDependencyVersion(file, uptodatePluginExtension.excludedVersionPatterns))]
            }
            return []
        }
        // "Cast" the closure to a Future (since that is what's expected...)
        return Executors.newSingleThreadExecutor().submit(cl as Callable)
    }

    private Version getLatestDependencyVersion(String releaseVersion, NodeChild xml, List<String> versionToExcludePatterns) {
        if (new VersionPatternMatcher(releaseVersion).matchesNoneOf(versionToExcludePatterns)) {
            return new Version(releaseVersion)
        }
        return xml.versioning.versions.version.findAll { NodeChild version ->
            new VersionPatternMatcher(version.text()).matchesNoneOf(versionToExcludePatterns)
        }.collect {
            NodeChild version -> new Version(version.text())
        }.max()
    }
    private Version getLatestDependencyVersion(File parentDir, List<String> versionToExcludePatterns) {
        return parentDir.listFiles().grep({f -> f.isDirectory()}).findAll {dir ->
            new VersionPatternMatcher(dir.name).matchesNoneOf(versionToExcludePatterns)
        }.collect {
            dir -> new Version(dir.name)
        }.max()
    }
}
