/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CxxDescriptionEnhancer {

  public static final Flavor HEADER_SYMLINK_TREE_FLAVOR = ImmutableFlavor.of("header-symlink-tree");
  public static final Flavor EXPORTED_HEADER_SYMLINK_TREE_FLAVOR =
      ImmutableFlavor.of("exported-header-symlink-tree");
  public static final Flavor STATIC_FLAVOR = ImmutableFlavor.of("static");
  public static final Flavor SHARED_FLAVOR = ImmutableFlavor.of("shared");

  public static final Flavor CXX_LINK_BINARY_FLAVOR = ImmutableFlavor.of("binary");

  public static final BuildRuleType LEX_TYPE = ImmutableBuildRuleType.of("lex");
  public static final BuildRuleType YACC_TYPE = ImmutableBuildRuleType.of("yacc");

  public static enum HeaderVisibility {
    PUBLIC,
    PRIVATE,
  }

  private CxxDescriptionEnhancer() {}

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the
   *    symlink tree of headers.
   */
  public static BuildTarget createHeaderSymlinkTreeTarget(
      BuildTarget target,
      Flavor platform,
      HeaderVisibility headerVisibility) {
    return BuildTarget
        .builder(target)
        .addFlavors(platform)
        .addFlavors(getHeaderSymlinkTreeFlavor(headerVisibility))
        .build();
  }

  /**
   * @return the {@link Path} to use for the symlink tree of headers.
   */
  public static Path getHeaderSymlinkTreePath(
      BuildTarget target,
      Flavor platform,
      HeaderVisibility headerVisibility) {
    return BuildTargets.getGenPath(
        createHeaderSymlinkTreeTarget(target, platform, headerVisibility),
        "%s");
  }

  public static Flavor getHeaderSymlinkTreeFlavor(HeaderVisibility headerVisibility) {
    switch (headerVisibility) {
      case PUBLIC:
        return EXPORTED_HEADER_SYMLINK_TREE_FLAVOR;
      case PRIVATE:
        return HEADER_SYMLINK_TREE_FLAVOR;
      default:
        throw new RuntimeException("Unexpected value of enum ExportMode");
    }
  }

  private static ImmutableMap<Path, SourcePath> getHeaderMapFromArgParameter(
      SourcePathResolver pathResolver,
      BuildTarget buildTarget,
      Optional<String> headerNamespace,
      String parameterName,
      Optional<Either<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>> parameter) {
    ImmutableMap<String, SourcePath> headers;
    if (!parameter.isPresent()) {
      headers = ImmutableMap.of();
    } else if (parameter.get().isRight()) {
      headers = parameter.get().getRight();
    } else {
      headers = pathResolver.getSourcePathNames(
          buildTarget,
          parameterName,
          parameter.get().getLeft());
    }
    return CxxPreprocessables.resolveHeaderMap(
        headerNamespace.transform(MorePaths.TO_PATH)
            .or(buildTarget.getBasePath()),
        headers);
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *    input {@link SourcePath} objects for the "headers" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseHeaders(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxConstructorArg args) {
    return getHeaderMapFromArgParameter(
        new SourcePathResolver(resolver),
        params.getBuildTarget(),
        args.headerNamespace,
        "headers",
        args.headers);
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *    input {@link SourcePath} objects for the "exportedHeaders" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxLibraryDescription.Arg args) {
    return getHeaderMapFromArgParameter(
        new SourcePathResolver(resolver),
        params.getBuildTarget(),
        args.headerNamespace,
        "exportedHeaders",
        args.exportedHeaders);
  }

  /**
   * @return a list {@link CxxSource} objects formed by parsing the input {@link SourcePath}
   *    objects for the "srcs" parameter.
   */
  public static ImmutableMap<String, CxxSource> parseCxxSources(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxConstructorArg args) {
    ImmutableMap<String, SourceWithFlags> sources;
    if (!args.srcs.isPresent()) {
      sources = ImmutableMap.of();
    } else if (args.srcs.get().isRight()) {
      sources = args.srcs.get().getRight();
    } else {
      SourcePathResolver pathResolver = new SourcePathResolver(resolver);
      sources = pathResolver.getSourcePathNames(
          params.getBuildTarget(),
          "srcs",
          args.srcs.get().getLeft(),
          SourceWithFlags.TO_SOURCE_PATH);
    }
    return CxxCompilableEnhancer.resolveCxxSources(sources);
  }

  public static ImmutableMap<String, SourcePath> parseLexSources(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxConstructorArg args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return pathResolver.getSourcePathNames(
        params.getBuildTarget(),
        "lexSrcs",
        args.lexSrcs.or(ImmutableList.<SourcePath>of()));
  }

  public static ImmutableMap<String, SourcePath> parseYaccSources(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxConstructorArg args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return pathResolver.getSourcePathNames(
        params.getBuildTarget(),
        "yaccSrcs",
        args.yaccSrcs.or(ImmutableList.<SourcePath>of()));
  }

  @VisibleForTesting
  protected static BuildTarget createLexBuildTarget(BuildTarget target, String name) {
    return BuildTarget
        .builder(target.getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "lex-%s",
                    name.replace('/', '-').replace('.', '-').replace('+', '-').replace(' ', '-'))))
        .build();
  }

  @VisibleForTesting
  protected static BuildTarget createYaccBuildTarget(BuildTarget target, String name) {
    return BuildTarget
        .builder(target.getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of(
                String.format(
                    "yacc-%s",
                    name.replace('/', '-').replace('.', '-').replace('+', '-').replace(' ', '-'))))
        .build();
  }

  /**
   * @return the output path prefix to use for yacc generated files.
   */
  @VisibleForTesting
  protected static Path getYaccOutputPrefix(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createYaccBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name);
  }

  /**
   * @return the output path to use for the lex generated C/C++ source.
   */
  @VisibleForTesting
  protected static Path getLexSourceOutputPath(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createLexBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name + ".cc");
  }

  /**
   * @return the output path to use for the lex generated C/C++ header.
   */
  @VisibleForTesting
  protected static Path getLexHeaderOutputPath(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createLexBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name + ".h");
  }

  /**
   * Generate {@link Lex} and {@link Yacc} rules generating C/C++ sources from the
   * given lex/yacc sources.
   *
   * @return {@link CxxHeaderSourceSpec} containing the generated headers/sources
   */
  public static CxxHeaderSourceSpec createLexYaccBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableList<String> lexFlags,
      ImmutableMap<String, SourcePath> lexSrcs,
      ImmutableList<String> yaccFlags,
      ImmutableMap<String, SourcePath> yaccSrcs) {
    if (!lexSrcs.isEmpty() && !cxxPlatform.getLex().isPresent()) {
      throw new HumanReadableException(
          "Platform %s must support lex to compile srcs %s",
          cxxPlatform,
          lexSrcs);
    }

    if (!yaccSrcs.isEmpty() && !cxxPlatform.getYacc().isPresent()) {
      throw new HumanReadableException(
          "Platform %s must support yacc to compile srcs %s",
          cxxPlatform,
          yaccSrcs);
    }

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableMap.Builder<String, CxxSource> lexYaccCxxSourcesBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<Path, SourcePath> lexYaccHeadersBuilder = ImmutableMap.builder();

    // Loop over all lex sources, generating build rule for each one and adding the sources
    // and headers it generates to our bookkeeping maps.
    for (ImmutableMap.Entry<String, SourcePath> ent : lexSrcs.entrySet()) {
      final String name = ent.getKey();
      final SourcePath source = ent.getValue();

      BuildTarget target = createLexBuildTarget(params.getBuildTarget(), name);
      Path outputSource = getLexSourceOutputPath(target, name);
      Path outputHeader = getLexHeaderOutputPath(target, name);

      // Create the build rule to run lex on this source and add it to the resolver.
      Lex lex = new Lex(
          params.copyWithChanges(
              LEX_TYPE,
              target,
              Suppliers.ofInstance(
                  ImmutableSortedSet.copyOf(
                      pathResolver.filterBuildRuleInputs(ImmutableList.of(source)))),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
          pathResolver,
          cxxPlatform.getLex().get(),
          ImmutableList.<String>builder()
              .addAll(cxxPlatform.getLexFlags())
              .addAll(lexFlags)
              .build(),
          outputSource,
          outputHeader,
          source);
      resolver.addToIndex(lex);

      // Record the output source and header as {@link BuildRuleSourcePath} objects.
      lexYaccCxxSourcesBuilder.put(
          name + ".cc",
          ImmutableCxxSource.of(
              CxxSource.Type.CXX,
              new BuildTargetSourcePath(
                  lex.getProjectFilesystem(),
                  lex.getBuildTarget(),
                  outputSource),
              ImmutableList.<String>of()));
      lexYaccHeadersBuilder.put(
          params.getBuildTarget().getBasePath().resolve(name + ".h"),
          new BuildTargetSourcePath(
              lex.getProjectFilesystem(),
              lex.getBuildTarget(),
              outputHeader));
    }

    // Loop over all yaccc sources, generating build rule for each one and adding the sources
    // and headers it generates to our bookkeeping maps.
    for (ImmutableMap.Entry<String, SourcePath> ent : yaccSrcs.entrySet()) {
      final String name = ent.getKey();
      final SourcePath source = ent.getValue();

      BuildTarget target = createYaccBuildTarget(params.getBuildTarget(), name);
      Path outputPrefix = getYaccOutputPrefix(target, Files.getNameWithoutExtension(name));

      // Create the build rule to run yacc on this source and add it to the resolver.
      Yacc yacc = new Yacc(
          params.copyWithChanges(
              YACC_TYPE,
              target,
              Suppliers.ofInstance(
                  ImmutableSortedSet.copyOf(
                      pathResolver.filterBuildRuleInputs(ImmutableList.of(source)))),
              Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
          pathResolver,
          cxxPlatform.getYacc().get(),
          ImmutableList.<String>builder()
              .addAll(cxxPlatform.getYaccFlags())
              .addAll(yaccFlags)
              .build(),
          outputPrefix,
          source);
      resolver.addToIndex(yacc);

      // Record the output source and header as {@link BuildRuleSourcePath} objects.
      lexYaccCxxSourcesBuilder.put(
          name + ".cc",
          ImmutableCxxSource.of(
              CxxSource.Type.CXX,
              new BuildTargetSourcePath(
                  yacc.getProjectFilesystem(),
                  yacc.getBuildTarget(),
                  Yacc.getSourceOutputPath(outputPrefix)),
              ImmutableList.<String>of()));

      lexYaccHeadersBuilder.put(
          params.getBuildTarget().getBasePath().resolve(name + ".h"),
          new BuildTargetSourcePath(
              yacc.getProjectFilesystem(),
              yacc.getBuildTarget(),
              Yacc.getHeaderOutputPath(outputPrefix)));
    }

    return ImmutableCxxHeaderSourceSpec.of(
        lexYaccHeadersBuilder.build(),
        lexYaccCxxSourcesBuilder.build());
  }

  public static SymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      Flavor platform,
      ImmutableMap<Path, SourcePath> headers,
      HeaderVisibility headerVisibility) {

    // Setup the header and symlink tree rules
    BuildTarget headerSymlinkTreeTarget =
        createHeaderSymlinkTreeTarget(params.getBuildTarget(), platform, headerVisibility);
    Path headerSymlinkTreeRoot =
        getHeaderSymlinkTreePath(params.getBuildTarget(), platform, headerVisibility);
    final SymlinkTree headerSymlinkTree = CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        new SourcePathResolver(resolver),
        headerSymlinkTreeTarget,
        params,
        headerSymlinkTreeRoot,
        headers);
    resolver.addToIndex(headerSymlinkTree);

    return headerSymlinkTree;
  }

  public static CxxPreprocessorInput combineCxxPreprocessorInput(
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableList<SymlinkTree> headerSymlinkTrees,
      ImmutableList<Path> frameworkSearchPaths) {

    CxxPreprocessorInput cxxPreprocessorInputFromDeps;
    try {
      // Write the compile rules for all C/C++ sources in this rule.
      cxxPreprocessorInputFromDeps =
          CxxPreprocessables.getTransitiveCxxPreprocessorInput(
              cxxPlatform,
              FluentIterable.from(params.getDeps())
                  .filter(Predicates.instanceOf(CxxPreprocessorDep.class)));
    } catch (CxxPreprocessorInput.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(params.getBuildTarget());
    }

    ImmutableMap.Builder<Path, SourcePath> allLinks = ImmutableMap.builder();
    ImmutableMap.Builder<Path, SourcePath> allFullLinks = ImmutableMap.builder();
    ImmutableList.Builder<Path> allIncludeRoots = ImmutableList.builder();
    for (SymlinkTree headerSymlinkTree : headerSymlinkTrees) {
      allLinks.putAll(headerSymlinkTree.getLinks());
      allFullLinks.putAll(headerSymlinkTree.getFullLinks());
      allIncludeRoots.add(headerSymlinkTree.getRoot());
    }

    CxxPreprocessorInput localPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addAllRules(Iterables.transform(headerSymlinkTrees, HasBuildTarget.TO_TARGET))
            .putAllPreprocessorFlags(preprocessorFlags)
            .setIncludes(
                ImmutableCxxHeaders.builder()
                    .addAllPrefixHeaders(prefixHeaders)
                    .putAllNameToPathMap(allLinks.build())
                    .putAllFullNameToPathMap(allFullLinks.build())
                    .build())
            .addAllIncludeRoots(allIncludeRoots.build())
            .addAllFrameworkRoots(frameworkSearchPaths)
            .build();

    try {
      return CxxPreprocessorInput.concat(
          ImmutableList.of(
              localPreprocessorInput,
              cxxPreprocessorInputFromDeps));
    } catch (CxxPreprocessorInput.ConflictingHeadersException e) {
      throw e.getHumanReadableExceptionForBuildTarget(params.getBuildTarget());
    }
  }

  /**
   * Build up the rules to track headers and compile sources for descriptions which handle C/C++
   * sources and headers.
   *
   * @return a list of {@link SourcePath} objects representing the object files from the result of
   *    compiling the given C/C++ source.
   */
  public static ImmutableList<SourcePath> createCompileBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform config,
      ImmutableList<String> compilerFlags,
      boolean pic,
      ImmutableMap<String, CxxSource> sources) {

    ImmutableSortedSet<BuildRule> objectRules = CxxCompilableEnhancer.createCompileBuildRules(
        params,
        resolver,
        config,
        compilerFlags,
        pic,
        sources);
    resolver.addAllToIndex(objectRules);

    return FluentIterable.from(objectRules)
        .transform(SourcePaths.getToBuildTargetSourcePath(params.getProjectFilesystem()))
        .toList();
  }

  public static BuildTarget createStaticLibraryBuildTarget(
      BuildTarget target,
      Flavor platform) {
    return BuildTarget.builder(target).addFlavors(platform).addFlavors(STATIC_FLAVOR).build();
  }

  public static BuildTarget createSharedLibraryBuildTarget(
      BuildTarget target,
      Flavor platform) {
    return BuildTarget.builder(target).addFlavors(platform).addFlavors(SHARED_FLAVOR).build();
  }

  public static Path getStaticLibraryPath(
      BuildTarget target,
      Flavor platform) {
    String name = String.format("lib%s.a", target.getShortName());
    return BuildTargets.getBinPath(createStaticLibraryBuildTarget(target, platform), "%s")
        .resolve(name);
  }

  public static String getSharedLibrarySoname(BuildTarget target, CxxPlatform platform) {
    String libName =
        Joiner.on('_').join(
            ImmutableList.builder()
                .addAll(
                    FluentIterable.from(target.getBasePath())
                        .transform(Functions.toStringFunction())
                        .filter(Predicates.not(Predicates.equalTo(""))))
                .add(target.getShortName())
                .build());
    String extension = platform.getSharedLibraryExtension();
    return String.format("lib%s.%s", libName, extension);
  }

  public static Path getSharedLibraryPath(
      BuildTarget target,
      CxxPlatform platform) {
    String extension = platform.getSharedLibraryExtension();
    String name = String.format("lib%s.%s", target.getShortName(), extension);
    return BuildTargets.getBinPath(
        createSharedLibraryBuildTarget(target, platform.getFlavor()),
        "%s/" + name);
  }

  @VisibleForTesting
  protected static Path getOutputPath(BuildTarget target) {
    return BuildTargets.getBinPath(target, "%s/" + target.getShortNameAndFlavorPostfix());
  }

  @VisibleForTesting
  protected static BuildTarget createCxxLinkTarget(BuildTarget target) {
    return BuildTarget.builder(target).addFlavors(CXX_LINK_BINARY_FLAVOR).build();
  }

  public static CxxLink createBuildRulesForCxxBinaryDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.Arg args) {

    ImmutableMap<String, CxxSource> srcs = parseCxxSources(params, resolver, args);
    ImmutableMap<Path, SourcePath> headers = parseHeaders(params, resolver, args);
    ImmutableMap<String, SourcePath> lexSrcs = parseLexSources(params, resolver, args);
    ImmutableMap<String, SourcePath> yaccSrcs = parseYaccSources(params, resolver, args);

    // Setup the rules to run lex/yacc.
    CxxHeaderSourceSpec lexYaccSources =
        createLexYaccBuildRules(
            params,
            resolver,
            cxxPlatform,
            ImmutableList.<String>of(),
            lexSrcs,
            ImmutableList.<String>of(),
            yaccSrcs);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    SymlinkTree headerSymlinkTree = createHeaderSymlinkTreeBuildRule(
        params,
        resolver,
        cxxPlatform.getFlavor(),
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(headers)
            .putAll(lexYaccSources.getCxxHeaders())
            .build(),
        HeaderVisibility.PRIVATE);
    CxxPreprocessorInput cxxPreprocessorInput = combineCxxPreprocessorInput(
        params,
        cxxPlatform,
        CxxPreprocessorFlags.fromArgs(
            args.preprocessorFlags,
            args.langPreprocessorFlags),
        args.prefixHeaders.get(),
        ImmutableList.of(headerSymlinkTree),
        args.frameworkSearchPaths.get());

    // The complete list of input sources.
    ImmutableMap<String, CxxSource> sources =
        ImmutableMap.<String, CxxSource>builder()
            .putAll(srcs)
            .putAll(lexYaccSources.getCxxSources())
            .build();

    // Generate whatever rules are needed to preprocess all the input sources.
    ImmutableMap<String, CxxSource> preprocessed =
        CxxPreprocessables.createPreprocessBuildRules(
            params,
            resolver,
            cxxPlatform,
            cxxPreprocessorInput,
            /* pic */ false,
            sources);

    // Generate the rules for setting up and headers, preprocessing, and compiling the input
    // sources and return the source paths for the object files.
    ImmutableList<SourcePath> objects =
        createCompileBuildRules(
            params,
            resolver,
            cxxPlatform,
            args.compilerFlags.or(ImmutableList.<String>of()),
            /* pic */ false,
            preprocessed);

    // Generate the final link rule.  We use the top-level target as the link rule's
    // target, so that it corresponds to the actual binary we build.
    Path output = getOutputPath(params.getBuildTarget());
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        new SourcePathResolver(resolver),
        /* extraCxxLdFlags */ ImmutableList.<String>of(),
        /* extraLdFlags */ ImmutableList.<String>builder()
            .addAll(args.linkerFlags.or(ImmutableList.<String>of()))
            .addAll(
                CxxDescriptionEnhancer.getPlatformFlags(
                    args.platformLinkerFlags.get(),
                    cxxPlatform.getFlavor().toString()))
            .build(),
        createCxxLinkTarget(params.getBuildTarget()),
        Linker.LinkType.EXECUTABLE,
        Optional.<String>absent(),
        output,
        objects,
        Linker.LinkableDepType.STATIC,
        params.getDeps());
    resolver.addToIndex(cxxLink);

    return cxxLink;
  }

  private static <T> BuildRule requireBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      TargetNode<T> node,
      Flavor... flavors) {
    BuildTarget target = BuildTarget.builder(params.getBuildTarget()).addFlavors(flavors).build();
    Optional<BuildRule> rule = ruleResolver.getRuleOptional(target);
    if (!rule.isPresent()) {
      Description<T> description = node.getDescription();
      T args = node.getConstructorArg();
      rule = Optional.of(
          description.createBuildRule(
              params.copyWithChanges(
                  params.getBuildRuleType(),
                  target,
                  Suppliers.ofInstance(params.getDeclaredDeps()),
                  Suppliers.ofInstance(params.getExtraDeps())),
              ruleResolver,
              args));
      ruleResolver.addToIndex(rule.get());
    }
    return rule.get();
  }

  /**
   * Ensure that the build rule generated by the given {@link BuildRuleParams} had been generated
   * by it's corresponding {@link Description} and added to the {@link BuildRuleResolver}.  If not,
   * call into it's associated {@link Description} to generate it's {@link BuildRule}.
   *
   * @return the {@link BuildRule} generated by the description corresponding to the supplied
   *     {@link BuildRuleParams}.
   */
  public static BuildRule requireBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      Flavor... flavors) {
    TargetNode<?> node = params.getTargetGraph().get(params.getBuildTarget());
    Preconditions.checkNotNull(
        node,
        String.format("%s not in target graph", params.getBuildTarget()));
    return requireBuildRule(params, ruleResolver, node, flavors);
  }

  /**
   * @return a {@link Function} object which transforms path names from the output of a compiler
   *     or preprocessor using {@code pathProcessor}.
   */
  public static Function<String, String> createErrorMessagePathProcessor(
      final Function<String, String> pathProcessor) {
    return new Function<String, String>() {

      private final ImmutableList<Pattern> patterns =
          ImmutableList.of(
              Pattern.compile(
                  "(?<=^(?:In file included |\\s+)from )" +
                  "(?<path>[^:]+)" +
                  "(?=[:,](?:\\d+[:,](?:\\d+[:,])?)?$)"),
              Pattern.compile(
                  "^(?<path>[^:]+)(?=:(?:\\d+:(?:\\d+:)?)? )"));

      @Override
      public String apply(String line) {
        for (Pattern pattern : patterns) {
          Matcher m = pattern.matcher(line);
          if (m.find()) {
            return m.replaceAll(pathProcessor.apply(m.group("path")));
          }
        }
        return line;
      }

    };
  }

  public static ImmutableList<String> getPlatformFlags(
      ImmutableList<Pair<String, ImmutableList<String>>> platformFlags,
      String platform) {

    ImmutableList.Builder<String> platformFlagsBuilder = ImmutableList.builder();

    for (Pair<String, ImmutableList<String>> pair : platformFlags) {
      Pattern pattern = Pattern.compile(pair.getFirst());
      Matcher matcher = pattern.matcher(platform);
      if (matcher.find()) {
        platformFlagsBuilder.addAll(pair.getSecond());
        break;
      }
    }

    return platformFlagsBuilder.build();
  }

}
