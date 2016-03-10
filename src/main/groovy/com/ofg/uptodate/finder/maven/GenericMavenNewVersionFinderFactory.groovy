package com.ofg.uptodate.finder.maven
import java.util.concurrent.Future
import com.ofg.uptodate.UptodatePluginExtension
import com.ofg.uptodate.dependency.Dependency
import com.ofg.uptodate.dependency.Version
import com.ofg.uptodate.dependency.VersionPatternMatcher
import com.ofg.uptodate.finder.FinderConfiguration
import com.ofg.uptodate.finder.NewVersionFinder
import com.ofg.uptodate.finder.RepositorySettings
import com.ofg.uptodate.finder.http.HTTPBuilderProvider
import groovy.util.logging.Slf4j
import groovy.util.slurpersupport.NodeChild
import groovyx.net.http.HTTPBuilder

import static com.ofg.uptodate.finder.http.HTTPBuilderProvider.FailureHandlers.logOnlyFailureHandler

@Slf4j
class GenericMavenNewVersionFinderFactory { // does not: implements NewVersionFinderFactory {

    @Override
    NewVersionFinder create(String repoUrl, UptodatePluginExtension uptodatePluginExtension, List<Dependency> dependencies) {
        FinderConfiguration finderConfiguration = new FinderConfiguration(
                new RepositorySettings(repoUrl: repoUrl, ignoreRepo: false),
                uptodatePluginExtension,
                dependencies.size())
        return new NewVersionFinder(
                MavenRepoLatestVersionsCollector(finderConfiguration),
                finderConfiguration)
    }

    private Closure<Future> MavenRepoLatestVersionsCollector(FinderConfiguration finderConfiguration) {
        HTTPBuilder httpBuilder = new HTTPBuilderProvider(finderConfiguration.httpConnectionSettings).get()
        return getLatestFromMavenRepo.curry(httpBuilder, finderConfiguration.excludedVersionPatterns)
    }

    private Closure<Future> getLatestFromMavenRepo = { HTTPBuilder httpBuilder, List<String> versionToExcludePatterns, Dependency dependency ->
        httpBuilder.handler.failure = logOnlyFailureHandler(log, dependency.name)
        handleApplicationUnknownContentTypeAsXml(httpBuilder)
        String path = httpBuilder.getUri().getPath()
        if (!path.startsWith('/')) {
            path = '/'.concat(path)
        }
        if (!path.endsWith('/')) {
            path = path.concat('/')
        }
        httpBuilder.get(path: "${path}${dependency.group.split('\\.').join('/')}/${dependency.name}/maven-metadata.xml", contentType : 'application/xml') { resp, xml ->
            if (!xml) {
                return []
            }
            try {
                return [dependency, new Dependency(dependency, getLatestDependencyVersion(xml.versioning.release.text(), xml, versionToExcludePatterns))]
            } catch (Exception e) {
                log.error("Exception occurred while trying to fetch latest dependencies. The fetched XML is [$xml]".toString(), e)
                return []
            }
        } as Future
    }

    //application/unknown is returned from jCenter when using CloudFront - #42
    private void handleApplicationUnknownContentTypeAsXml(HTTPBuilder httpBuilder) {
        httpBuilder.parser.'application/unknown' = httpBuilder.parser.'application/xml'
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
}
