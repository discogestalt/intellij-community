// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins;

import com.intellij.diagnostic.*;
import com.intellij.ide.WindowsCommandLineListener;
import com.intellij.ide.WindowsCommandLineProcessor;
import com.intellij.idea.Main;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

public final class MainRunner {
  @SuppressWarnings("StaticNonFinalField") public static WindowsCommandLineListener LISTENER;
  @SuppressWarnings("StaticNonFinalField") public static Activity startupStart;

  /** Called via reflection from {@link com.intellij.ide.Bootstrap#main}. */
  @SuppressWarnings("UnusedDeclaration")
  private static void start(@NotNull String mainClass,
                            @NotNull String methodName,
                            @NotNull String[] args,
                            @NotNull LinkedHashMap<String, Long> startupTimings) {
    StartUpMeasurer.addTimings(startupTimings, "bootstrap");

    startupStart = StartUpMeasurer.start(StartUpMeasurer.Phases.PREPARE_TO_INIT_APP);

    Main.setFlags(args);

    ThreadGroup threadGroup = new ThreadGroup("Idea Thread Group") {
      @Override
      public void uncaughtException(Thread t, Throwable e) {
        processException(e);
      }
    };

    Runnable runnable = () -> {
      try {
        Activity activity = startupStart.startChild(StartUpMeasurer.Phases.LOAD_MAIN_CLASS);
        Class<?> aClass = Class.forName(mainClass);
        Method method = aClass.getDeclaredMethod(methodName, String[].class);
        method.setAccessible(true);
        Object[] argsArray = {args};
        activity.end();
        method.invoke(null, argsArray);
      }
      catch (Throwable t) {
        throw new StartupAbortedException(t);
      }
    };

    new Thread(threadGroup, runnable, "Idea Main Thread").start();
  }

  public static void processException(@NotNull Throwable t) {
    if (LoadingPhase.COMPONENT_LOADED.isComplete()) {
      if (!(t instanceof ProcessCanceledException)) {
        PluginManagerCore.getLogger().error(t);
      }
      return;
    }

    PluginManagerCore.EssentialPluginMissingException pme = findCause(t, PluginManagerCore.EssentialPluginMissingException.class);
    if (pme != null && pme.pluginIds != null) {
      Main.showMessage("Corrupted Installation",
                       "Missing essential " + (pme.pluginIds.size() == 1 ? "plugin" : "plugins") + ":\n\n" +
                       pme.pluginIds.stream().sorted().collect(Collectors.joining("\n  ", "  ", "\n\n")) +
                       "Please reinstall " + getProductNameSafe() + " from scratch.", true);
      System.exit(Main.INSTALLATION_CORRUPTED);
    }

    StartupAbortedException startupException = findCause(t, StartupAbortedException.class);
    if (startupException == null) startupException = new StartupAbortedException(t);
    PluginException pluginException = findCause(t, PluginException.class);
    PluginId pluginId = pluginException != null ? pluginException.getPluginId() : null;

    if (Logger.isInitialized() && !(t instanceof ProcessCanceledException)) {
      try {
        PluginManagerCore.getLogger().error(t);
      }
      catch (Throwable ignore) { }

      // workaround for SOE on parsing PAC file (JRE-247)
      if (t instanceof StackOverflowError && "Nashorn AST Serializer".equals(Thread.currentThread().getName())) {
        return;
      }
    }

    if (LoadingPhase.COMPONENT_REGISTERED.isComplete()) {
      ImplementationConflictException conflictException = findCause(t, ImplementationConflictException.class);
      if (conflictException != null) {
        PluginConflictReporter pluginConflictReporter = ApplicationManager.getApplication().getService(PluginConflictReporter.class, true);
        pluginConflictReporter.reportConflictByClasses(conflictException.getConflictingClasses());
      }
    }

    if (pluginId != null && !ApplicationInfoImpl.getShadowInstance().isEssentialPlugin(pluginId.getIdString())) {
      PluginManagerCore.disablePlugin(pluginId.getIdString());

      StringWriter message = new StringWriter();
      message.append("Plugin '").append(pluginId.getIdString()).append("' failed to initialize and will be disabled. ");
      message.append(" Please restart ").append(getProductNameSafe()).append('.');
      message.append("\n\n");
      pluginException.getCause().printStackTrace(new PrintWriter(message));

      Main.showMessage("Plugin Error", message.toString(), false);
      System.exit(Main.PLUGIN_ERROR);
    }
    else {
      Main.showMessage("Start Failed", t);
      System.exit(startupException.exitCode());
    }
  }

  private static String getProductNameSafe() {
    try {
      return ApplicationNamesInfo.getInstance().getFullProductName();
    }
    catch (Throwable ignore) {
      return "the IDE";
    }
  }

  private static <T extends Throwable> T findCause(Throwable t, Class<T> clazz) {
    while (t != null) {
      if (clazz.isInstance(t)) {
        return clazz.cast(t);
      }
      t = t.getCause();
    }
    return null;
  }

  static class StartupAbortedException extends RuntimeException {
    private int exitCode = Main.STARTUP_EXCEPTION;

    StartupAbortedException(Throwable cause) {
      super(cause);
    }

    StartupAbortedException(String message, Throwable cause) {
      super(message, cause);
    }

    public int exitCode() {
      return exitCode;
    }

    @NotNull
    public StartupAbortedException exitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }
  }

  /** Called via reflection from {@link WindowsCommandLineProcessor#processWindowsLauncherCommandLine}. */
  @SuppressWarnings("UnusedDeclaration")
  public static int processWindowsLauncherCommandLine(final String currentDirectory, final String[] args) {
    return LISTENER != null ? LISTENER.processWindowsLauncherCommandLine(currentDirectory, args) : 1;
  }
}