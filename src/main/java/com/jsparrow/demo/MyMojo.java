package com.jsparrow.demo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/** Goal which touches a timestamp file. */
@Mojo(name = "hello", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class MyMojo extends AbstractMojo {

  private final Log log = getLog();

  /** Location of the file. */
  @Parameter(defaultValue = "${basedir}", property = "outputDir", required = true)
  private File basedir;

  private static final String PROJECT_ROOT = "project_root";

  public void execute() throws MojoExecutionException {
    // We move to temporary directory which will be our workspace location
    System.setProperty("user.dir", createTemporaryDirectory());

    Framework framework = startFramework();
    Bundle mainBundle = loadBundles(framework);
    try {
      mainBundle.start();
      stop(framework, mainBundle);
    } catch (BundleException e) {
      throw new MojoExecutionException("Bundle start failed", e);
    }
  }

  private Bundle loadBundles(Framework framework) {
    File packagesFile = locateBundleOutputDirectory();

    log.info("Packages location: " + packagesFile.toString());
    List<Bundle> bundles = new ArrayList<>();
    Stream.of(packagesFile.listFiles())
        // Skip org.eclipse.osgi since it is loaded by default with framework
        .filter(p -> !p.getName().startsWith("org.eclipse.osgi_"))
        .peek(p -> log.info("Installing: " + p))
        .forEach(
            p -> {
              try {
                bundles.add(
                    framework
                        .getBundleContext()
                        .installBundle(p.getName(), new FileInputStream(p)));
              } catch (BundleException | FileNotFoundException e) {
                log.error(e);
              }
            });

    Bundle mainBundle =
        bundles
            .stream()
            .filter(p -> p.getSymbolicName().equals("hellosparrow.core"))
            .peek(p -> log.info(p.getSymbolicName()))
            .findFirst()
            .get();
    return mainBundle;
  }

  private File locateBundleOutputDirectory() {
    return Paths.get(System.getProperty("user.home"))
        .resolve("git")
        .resolve("hellosparrow")
        .resolve("hellosparrow.site")
        .resolve("target")
        .resolve("repository")
        .resolve("plugins")
        .toFile();
  }

  private Framework startFramework() throws MojoExecutionException {
    Map<String, String> configuration = new HashMap<>();
    configuration.put(
        Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    configuration.put(Constants.FRAMEWORK_STORAGE, createTemporaryDirectory());
    configuration.put(Constants.FRAMEWORK_BOOTDELEGATION, "javax.*,org.xml.*");
    configuration.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, "helloworld1.core");

    // Set Eclipse plugin parameter for non GUI execution
    configuration.put(PROJECT_ROOT, basedir.getAbsolutePath());

    ServiceLoader<FrameworkFactory> ffs = ServiceLoader.load(FrameworkFactory.class);
    FrameworkFactory frameworkFactory = ffs.iterator().next();
    Framework framework = frameworkFactory.newFramework(configuration);
    try {
      framework.start();
    } catch (BundleException e) {
      throw new MojoExecutionException("Unable to start framework", e);
    }
    return framework;
  }

  private String createTemporaryDirectory() throws MojoExecutionException {
    try {
      return Files.createTempDirectory("sparrow").toString();
    } catch (IOException e) {
      throw new MojoExecutionException("Temp file unavailable", e);
    }
  }

  private void stop(Framework framework, Bundle mainBundle) {
    if (null != framework && null != framework.getBundleContext()) {
      try {
        Bundle standaloneBundle = framework.getBundleContext().getBundle(mainBundle.getBundleId());
        if (standaloneBundle.getState() == Bundle.ACTIVE) {
          standaloneBundle.stop();
        }

        framework.stop();
        framework.waitForStop(0);

        log.info("Bundle stopped");

      } catch (BundleException | InterruptedException e) {
        log.debug(e.getMessage(), e);
        log.error(e.getMessage());
      }
    }
  }
}
