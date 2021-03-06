/*
 * The MIT License
 *
 * Copyright (c) 2016-2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.bitbucket;

import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketApi;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketApiFactory;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBranch;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketCommit;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketHref;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketPullRequest;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepository;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepositoryProtocol;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepositoryType;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRequestException;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketTeam;
import com.cloudbees.jenkins.plugins.bitbucket.client.BitbucketCloudApiClient;
import com.cloudbees.jenkins.plugins.bitbucket.client.repository.UserRoleInRepository;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.AbstractBitbucketEndpoint;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.BitbucketCloudEndpoint;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.BitbucketEndpointConfiguration;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.RestrictedSince;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.model.queue.Tasks;
import hudson.plugins.git.GitSCM;
import hudson.plugins.mercurial.MercurialSCM;
import hudson.plugins.mercurial.traits.MercurialBrowserSCMSourceTrait;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.plugins.git.AbstractGitSCMSource.SCMRevisionImpl;
import jenkins.plugins.git.traits.GitBrowserSCMSourceTrait;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceEvent;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.metadata.ContributorMetadataAction;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.api.metadata.PrimaryInstanceMetadataAction;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import jenkins.scm.api.trait.SCMSourceRequest;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import jenkins.scm.impl.ChangeRequestSCMHeadCategory;
import jenkins.scm.impl.UncategorizedSCMHeadCategory;
import jenkins.scm.impl.form.NamedArrayList;
import jenkins.scm.impl.trait.Discovery;
import jenkins.scm.impl.trait.Selection;
import jenkins.scm.impl.trait.WildcardSCMHeadFilterTrait;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.eclipse.jgit.lib.Constants;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * SCM source implementation for Bitbucket.
 * 
 * It provides a way to discover/retrieve branches and pull requests through the Bitbuclet REST API
 * which is much faster than the plain Git SCM source implementation.
 */
public class BitbucketSCMSource extends SCMSource {

    private static final Logger LOGGER = Logger.getLogger(BitbucketSCMSource.class.getName());

    /**
     * Bitbucket URL.
     */
    @NonNull
    private String serverUrl = BitbucketCloudEndpoint.SERVER_URL;

    /**
     * Credentials used to access the Bitbucket REST API.
     */
    @CheckForNull
    private String credentialsId;

    /**
     * Repository owner.
     * Used to build the repository URL.
     */
    @NonNull
    private final String repoOwner;

    /**
     * Repository name.
     * Used to build the repository URL.
     */
    @NonNull
    private final String repository;

    /**
     * The behaviours to apply to this source.
     */
    @NonNull
    private List<SCMSourceTrait> traits;

    /**
     * Credentials used to clone the repository/repositories.
     */
    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    private transient String checkoutCredentialsId;

    /**
     * Ant match expression that indicates what branches to include in the retrieve process.
     */
    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    private transient String includes;

    /**
     * Ant match expression that indicates what branches to exclude in the retrieve process.
     */
    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    private transient String excludes;

    /**
     * If true, a webhook will be auto-registered in the repository managed by this source.
     */
    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    private transient boolean autoRegisterHook;

    /**
     * Bitbucket Server URL.
     * An specific HTTP client is used if this field is not null.
     */
    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    private transient String bitbucketServerUrl;

    /**
     * The cache of the repository type.
     */
    @CheckForNull
    private transient BitbucketRepositoryType repositoryType;

    /**
     * The cache of pull request titles for each open PR.
     */
    @CheckForNull
    private transient /*effectively final*/ Map<String, String> pullRequestTitleCache;
    /**
     * The cache of pull request contributors for each open PR.
     */
    @CheckForNull
    private transient /*effectively final*/ Map<String, ContributorMetadataAction> pullRequestContributorCache;
    /**
     * The cache of the clone links.
     */
    @CheckForNull
    private transient List<BitbucketHref> cloneLinks = null;

    /**
     * Constructor.
     *
     * @param repoOwner  the repository owner.
     * @param repository the repository name.
     * @since 2.2.0
     */
    @DataBoundConstructor
    public BitbucketSCMSource(@NonNull String repoOwner, @NonNull String repository) {
        this.serverUrl = BitbucketCloudEndpoint.SERVER_URL;
        this.repoOwner = repoOwner;
        this.repository = repository;
        this.traits = new ArrayList<>();
    }

    /**
     * Legacy Constructor.
     *
     * @param id         the id.
     * @param repoOwner  the repository owner.
     * @param repository the repository name.
     * @deprecated use {@link #BitbucketSCMSource(String, String)} and {@link #setId(String)}
     */
    @Deprecated
    public BitbucketSCMSource(@CheckForNull String id, @NonNull String repoOwner, @NonNull String repository) {
        this(repoOwner, repository);
        setId(id);
        traits.add(new BranchDiscoveryTrait(true, true));
        traits.add(new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.MERGE)));
        traits.add(new ForkPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.MERGE),
                new ForkPullRequestDiscoveryTrait.TrustTeamForks()));
    }

    /**
     * Migrate legacy serialization formats.
     *
     * @return {@code this}
     * @throws ObjectStreamException if things go wrong.
     */
    @SuppressWarnings("ConstantConditions")
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
                        justification = "Only non-null after we set them here!")
    private Object readResolve() throws ObjectStreamException {
        if (serverUrl == null) {
            serverUrl = BitbucketEndpointConfiguration.get().readResolveServerUrl(bitbucketServerUrl);
        }
        if (traits == null) {
            traits = new ArrayList<>();
            if (!"*".equals(includes) || !"".equals(excludes)) {
                traits.add(new WildcardSCMHeadFilterTrait(includes, excludes));
            }
            if (checkoutCredentialsId != null
                    && !DescriptorImpl.SAME.equals(checkoutCredentialsId)
                    && !checkoutCredentialsId.equals(credentialsId)) {
                traits.add(new SSHCheckoutTrait(checkoutCredentialsId));
            }
            traits.add(new WebhookRegistrationTrait(
                    autoRegisterHook ? WebhookRegistration.ITEM : WebhookRegistration.DISABLE)
            );
            traits.add(new BranchDiscoveryTrait(true, true));
            traits.add(new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.HEAD)));
            traits.add(new ForkPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.HEAD),
                    new ForkPullRequestDiscoveryTrait.TrustEveryone()));
            traits.add(new PublicRepoPullRequestFilterTrait());
        }
        return this;
    }

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(@CheckForNull String credentialsId) {
        this.credentialsId = Util.fixEmpty(credentialsId);
    }

    @NonNull
    public String getRepoOwner() {
        return repoOwner;
    }

    @NonNull
    public String getRepository() {
        return repository;
    }

    @NonNull
    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(@CheckForNull String serverUrl) {
        this.serverUrl = BitbucketEndpointConfiguration.normalizeServerUrl(serverUrl);
    }

    @NonNull
    public List<SCMSourceTrait> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    @DataBoundSetter
    public void setTraits(@CheckForNull List<SCMSourceTrait> traits) {
        this.traits = new ArrayList<>(Util.fixNull(traits));
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @DataBoundSetter
    public void setBitbucketServerUrl(String url) {
        url = BitbucketEndpointConfiguration.normalizeServerUrl(url);
        AbstractBitbucketEndpoint endpoint = BitbucketEndpointConfiguration.get().findEndpoint(url);
        if (endpoint != null) {
            // we have a match
            setServerUrl(endpoint.getServerUrl());
            return;
        }
        LOGGER.log(Level.WARNING, "Call to legacy setBitbucketServerUrl({0}) method is configuring an url missing "
                + "from the global configuration.", url);
        setServerUrl(url);
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @CheckForNull
    public String getBitbucketServerUrl() {
        String serverUrl = getServerUrl();
        if (BitbucketEndpointConfiguration.get().findEndpoint(serverUrl) instanceof BitbucketCloudEndpoint) {
            return null;
        }
        return serverUrl;
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @CheckForNull
    public String getCheckoutCredentialsId() {
        for (SCMSourceTrait t : traits) {
            if (t instanceof SSHCheckoutTrait) {
                return StringUtils.defaultString(((SSHCheckoutTrait) t).getCredentialsId(), DescriptorImpl.ANONYMOUS);
            }
        }
        return DescriptorImpl.SAME;
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @DataBoundSetter
    public void setCheckoutCredentialsId(String checkoutCredentialsId) {
        for (Iterator<SCMSourceTrait> iterator = traits.iterator(); iterator.hasNext(); ) {
            if (iterator.next() instanceof SSHCheckoutTrait) {
                iterator.remove();
            }
        }
        if (checkoutCredentialsId != null && !DescriptorImpl.SAME.equals(checkoutCredentialsId)) {
            traits.add(new SSHCheckoutTrait(checkoutCredentialsId));
        }
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @NonNull
    public String getIncludes() {
        for (SCMSourceTrait trait : traits) {
            if (trait instanceof WildcardSCMHeadFilterTrait) {
                return ((WildcardSCMHeadFilterTrait) trait).getIncludes();
            }
        }
        return "*";
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @DataBoundSetter
    public void setIncludes(@NonNull String includes) {
        for (int i = 0; i < traits.size(); i++) {
            SCMSourceTrait trait = traits.get(i);
            if (trait instanceof WildcardSCMHeadFilterTrait) {
                WildcardSCMHeadFilterTrait existing = (WildcardSCMHeadFilterTrait) trait;
                if ("*".equals(includes) && "".equals(existing.getExcludes())) {
                    traits.remove(i);
                } else {
                    traits.set(i, new WildcardSCMHeadFilterTrait(includes, existing.getExcludes()));
                }
                return;
            }
        }
        if (!"*".equals(includes)) {
            traits.add(new WildcardSCMHeadFilterTrait(includes, ""));
        }
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @NonNull
    public String getExcludes() {
        for (SCMSourceTrait trait : traits) {
            if (trait instanceof WildcardSCMHeadFilterTrait) {
                return ((WildcardSCMHeadFilterTrait) trait).getExcludes();
            }
        }
        return "";
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @DataBoundSetter
    public void setExcludes(@NonNull String excludes) {
        for (int i = 0; i < traits.size(); i++) {
            SCMSourceTrait trait = traits.get(i);
            if (trait instanceof WildcardSCMHeadFilterTrait) {
                WildcardSCMHeadFilterTrait existing = (WildcardSCMHeadFilterTrait) trait;
                if ("*".equals(existing.getIncludes()) && "".equals(excludes)) {
                    traits.remove(i);
                } else {
                    traits.set(i, new WildcardSCMHeadFilterTrait(existing.getIncludes(), excludes));
                }
                return;
            }
        }
        if (!"".equals(excludes)) {
            traits.add(new WildcardSCMHeadFilterTrait("*", excludes));
        }
    }


    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    @DataBoundSetter
    public void setAutoRegisterHook(boolean autoRegisterHook) {
        for (Iterator<SCMSourceTrait> iterator = traits.iterator(); iterator.hasNext(); ) {
            if (iterator.next() instanceof WebhookRegistrationTrait) {
                iterator.remove();
            }
        }
        traits.add(new WebhookRegistrationTrait(
                autoRegisterHook ? WebhookRegistration.ITEM : WebhookRegistration.DISABLE
        ));
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("2.2.0")
    public boolean isAutoRegisterHook() {
        for (SCMSourceTrait t : traits) {
            if (t instanceof WebhookRegistrationTrait) {
                return ((WebhookRegistrationTrait) t).getMode() != WebhookRegistration.DISABLE;
            }
        }
        return true;
    }

    public BitbucketRepositoryType getRepositoryType() throws IOException, InterruptedException {
        if (repositoryType == null) {
            BitbucketRepository r = buildBitbucketClient().getRepository();
            repositoryType = BitbucketRepositoryType.fromString(r.getScm());
            Map<String, List<BitbucketHref>> links = r.getLinks();
            if (links != null && links.containsKey("clone")) {
                cloneLinks = links.get("clone");
            }
        }
        return repositoryType;
    }

    public BitbucketApi buildBitbucketClient() {
        return BitbucketApiFactory.newInstance(getServerUrl(), credentials(), repoOwner, repository);
    }

    public BitbucketApi buildBitbucketClient(PullRequestSCMHead head) {
        return BitbucketApiFactory.newInstance(getServerUrl(), credentials(), head.getRepoOwner(), head.getRepository());
    }

    @Override
    public void afterSave() {
        try {
            getRepositoryType();
        } catch (InterruptedException | IOException e) {
            LOGGER.log(Level.FINE,
                    "Could not determine repository type of " + getRepoOwner() + "/" + getRepository() + " on "
                            + getServerUrl() + " for " + getOwner(), e);
        }
    }

    @Override
    protected void retrieve(@CheckForNull SCMSourceCriteria criteria, @NonNull SCMHeadObserver observer,
                            @CheckForNull SCMHeadEvent<?> event, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        try (BitbucketSCMSourceRequest request = new BitbucketSCMSourceContext(criteria, observer)
                .withTraits(traits)
                .newRequest(this, listener)) {
            StandardUsernamePasswordCredentials scanCredentials = credentials();
            if (scanCredentials == null) {
                listener.getLogger().format("Connecting to %s with no credentials, anonymous access%n", getServerUrl());
            } else {
                listener.getLogger().format("Connecting to %s using %s%n", getServerUrl(),
                        CredentialsNameProvider.name(scanCredentials));
            }
            // this has the side-effect of ensuring that repository type is always populated.
            listener.getLogger().format("Repository type: %s%n", WordUtils.capitalizeFully(getRepositoryType().name()));
            // populate the request with its data sources
            if (request.isFetchPRs()) {
                request.setPullRequests(new LazyIterable<BitbucketPullRequest>() {
                    @Override
                    protected Iterable<BitbucketPullRequest> create() {
                        try {
                            return (Iterable<BitbucketPullRequest>) buildBitbucketClient().getPullRequests();
                        } catch (IOException | InterruptedException e) {
                            throw new BitbucketSCMSource.WrappedException(e);
                        }
                    }
                });
            }
            if (request.isFetchBranches()) {
                request.setBranches(new LazyIterable<BitbucketBranch>() {
                    @Override
                    protected Iterable<BitbucketBranch> create() {
                        try {
                            return (Iterable<BitbucketBranch>) buildBitbucketClient().getBranches();
                        } catch (IOException | InterruptedException e) {
                            throw new BitbucketSCMSource.WrappedException(e);
                        }
                    }
                });
            }
            if (request.isFetchTags()) {
                // TODO request.setTags(...);
            }

            // now server the request
            if (request.isFetchBranches() && !request.isComplete()) {
                // Search branches
                retrieveBranches(request);
            }
            if (request.isFetchPRs() && !request.isComplete()) {
                // Search pull requests
                retrievePullRequests(request);
            }
            if (request.isFetchTags() && !request.isComplete()) {
                // TODO
            }
        } catch (WrappedException e) {
            e.unwrap();
        }
    }

    private void retrievePullRequests(final BitbucketSCMSourceRequest request)
            throws IOException, InterruptedException {
        final String fullName = repoOwner + "/" + repository;

        class Skip extends IOException {
        }

        final BitbucketApi originBitbucket = buildBitbucketClient();
        if (request.isSkipPublicPRs() && !originBitbucket.isPrivate()) {
            request.listener().getLogger().printf("Skipping pull requests for %s (public repository)%n", fullName);
            return;
        }

        request.listener().getLogger().printf("Looking up %s for pull requests%n", fullName);
        final Set<String> livePRs = new HashSet<>();
        int count = 0;
        Map<Boolean, Set<ChangeRequestCheckoutStrategy>> strategies = request.getPRStrategies();
        for (final BitbucketPullRequest pull : request.getPullRequests()) {
            request.listener().getLogger().printf(
                    "Checking PR-%s from %s and branch %s%n",
                    pull.getId(),
                    pull.getSource().getRepository().getFullName(),
                    pull.getSource().getBranch().getName()
            );
            boolean fork = !fullName.equalsIgnoreCase(pull.getSource().getRepository().getFullName());
            String pullRepoOwner = pull.getSource().getRepository().getOwnerName();
            String pullRepository = pull.getSource().getRepository().getRepositoryName();
            final BitbucketApi pullBitbucket = fork && originBitbucket instanceof BitbucketCloudApiClient
                    ? BitbucketApiFactory.newInstance(
                    getServerUrl(),
                    credentials(),
                    pullRepoOwner,
                    pullRepository
            )
                    : originBitbucket;
            count++;
            livePRs.add(pull.getId());
            getPullRequestTitleCache()
                    .put(pull.getId(), StringUtils.defaultString(pull.getTitle()));
            getPullRequestContributorCache().put(pull.getId(),
                    // TODO get more details on the author
                    new ContributorMetadataAction(pull.getAuthorLogin(), null, null)
            );
            try {
                // We store resolved hashes here so to avoid resolving the commits multiple times
                for (final ChangeRequestCheckoutStrategy strategy : strategies.get(fork)) {
                    final String branchName;
                    if (strategies.get(fork).size() == 1) {
                        branchName = "PR-" + pull.getId();
                    } else {
                        branchName = "PR-" + pull.getId() + "-" + strategy.name().toLowerCase(Locale.ENGLISH);
                    }
                    if (request.process(
                            new PullRequestSCMHead(branchName,
                                    pullRepoOwner,
                                    pullRepository,
                                    repositoryType,
                                    pull.getSource().getBranch().getName(),
                                    pull,
                                    originOf(pullRepoOwner, pullRepository),
                                    strategy
                            ),
                            new SCMSourceRequest.IntermediateLambda<String>() {
                                @Nullable
                                @Override
                                public String create() throws IOException, InterruptedException {
                                    try {
                                        return originBitbucket.resolveSourceFullHash(pull);
                                    } catch (BitbucketRequestException e) {
                                        if (originBitbucket instanceof BitbucketCloudApiClient) {
                                            if (e.getHttpCode() == 403) {
                                                request.listener().getLogger().printf("Skipping %s because of %s%n",
                                                        pull.getId(), HyperlinkNote.encodeTo(
                                                                "https://bitbucket.org/site/master"
                                                                        + "/issues/5814/reify-pull-requests"
                                                                        + "-by-making-them-a-ref",
                                                                "a permission issue accessing pull requests "
                                                                        + "from forks"));
                                                throw new Skip();
                                            }
                                        }
                                        // https://bitbucket
                                        // .org/site/master/issues/5814/reify-pull-requests-by-making-them-a-ref
                                        e.printStackTrace(request.listener().getLogger());
                                        if (e.getHttpCode() == 403) {
                                            // the credentials do not have permission, so we should not observe the
                                            // PR ever the PR is dead to us, so this is the one case where we can
                                            // squash the exception.
                                            throw new Skip();
                                        }
                                        throw e;
                                    }
                                }
                            },
                            new BitbucketProbeFactory(pullBitbucket, request),
                            new BitbucketRevisionFactory() {
                                @NonNull
                                @Override
                                public SCMRevision create(@NonNull SCMHead head, @Nullable String hash)
                                        throws IOException, InterruptedException {
                                    if (head instanceof PullRequestSCMHead) {
                                        PullRequestSCMHead h = (PullRequestSCMHead) head;
                                        for (BitbucketBranch b : request.getBranches()) {
                                            if (b.getName().equals(h.getTarget().getName())) {
                                                if (repositoryType == BitbucketRepositoryType.MERCURIAL) {
                                                    return new PullRequestSCMRevision<>(
                                                            h,
                                                            new MercurialRevision(h.getTarget(), b.getRawNode()),
                                                            new MercurialRevision(h, hash)
                                                    );
                                                } else {
                                                    return new PullRequestSCMRevision<>(h,
                                                            new SCMRevisionImpl(
                                                                    h.getTarget(),
                                                                    b.getRawNode()
                                                            ),
                                                            new SCMRevisionImpl(
                                                                    h,
                                                                    hash
                                                            )
                                                    );
                                                }
                                            }
                                        }
                                    }
                                    return super.create(head, hash);
                                }
                            }, new CriteriaWitness(request))) {
                        request.listener().getLogger()
                                .format("%n  %d pull requests were processed (query completed)%n", count);
                        return;
                    }
                }
            } catch (Skip e) {
                request.listener().getLogger().println(
                        "Do not have permission to view PR from " + pull.getSource().getRepository()
                                .getFullName()
                                + " and branch "
                                + pull.getSource().getBranch().getName());
                continue;
            }
        }
        request.listener().getLogger().format("%n  %d pull requests were processed%n", count);
        getPullRequestTitleCache().keySet().retainAll(livePRs);
        getPullRequestContributorCache().keySet().retainAll(livePRs);
    }

    private void retrieveBranches(final BitbucketSCMSourceRequest request)
            throws IOException, InterruptedException {
        String fullName = repoOwner + "/" + repository;
        request.listener().getLogger().println("Looking up " + fullName + " for branches");

        final BitbucketApi bitbucket = buildBitbucketClient();
        Map<String, List<BitbucketHref>> links = bitbucket.getRepository().getLinks();
        if (links != null && links.containsKey("clone")) {
            cloneLinks = links.get("clone");
        }
        int count = 0;
        for (final BitbucketBranch branch : request.getBranches()) {
            request.listener().getLogger().println("Checking branch " + branch.getName() + " from " + fullName);
            count++;
            if (request.process(new BranchSCMHead(branch.getName(), repositoryType),
                    new SCMSourceRequest.IntermediateLambda<String>() {
                        @Nullable
                        @Override
                        public String create() {
                            return branch.getRawNode();
                        }
                    }, new BitbucketProbeFactory(bitbucket, request), new BitbucketRevisionFactory(),
                    new CriteriaWitness(request)
            )) {
                request.listener().getLogger().format("%n  %d branches were processed (query completed)%n", count);
                return;
            }
        }
        request.listener().getLogger().format("%n  %d branches were processed%n", count);
    }

    @Override
    protected SCMRevision retrieve(SCMHead head, TaskListener listener) throws IOException, InterruptedException {
        List<? extends BitbucketBranch> branches = buildBitbucketClient().getBranches();
        if (head instanceof PullRequestSCMHead) {
            PullRequestSCMHead h = (PullRequestSCMHead) head;
            String targetRevision = findRawNode(h.getTarget().getName(), branches, listener);
            if (targetRevision == null) {
                LOGGER.log(Level.WARNING, "No branch found in {0}/{1} with name [{2}]",
                        new Object[]{repoOwner, repository, h.getTarget().getName()});
                return null;
            }
            branches = head.getOrigin() == SCMHeadOrigin.DEFAULT
                    ? branches
                    : buildBitbucketClient(h).getBranches();
            String sourceRevision = findRawNode(h.getBranchName(), branches, listener);
            if (sourceRevision == null) {
                LOGGER.log(Level.WARNING, "No branch found in {0}/{1} with name [{2}]",
                        new Object[]{
                                h.getRepoOwner(),
                                h.getRepository(),
                                h.getBranchName()
                        });
                return null;
            }
            if (getRepositoryType() == BitbucketRepositoryType.MERCURIAL) {
                return new PullRequestSCMRevision<>(
                        h,
                        new MercurialRevision(h.getTarget(), targetRevision),
                        new MercurialRevision(h, sourceRevision)
                );
            } else {
                return new PullRequestSCMRevision<>(
                        h,
                        new SCMRevisionImpl(h.getTarget(), targetRevision),
                        new SCMRevisionImpl(h, sourceRevision)
                );
            }
        } else {
            String revision = findRawNode(head.getName(), branches, listener);
            if (revision == null) {
                LOGGER.log(Level.WARNING, "No branch found in {0}/{1} with name [{2}]",
                        new Object[]{repoOwner, repository, head.getName()});
                return null;
            }
            if (getRepositoryType() == BitbucketRepositoryType.MERCURIAL) {
                return new MercurialRevision(head, revision);
            } else {
                return new SCMRevisionImpl(head, revision);
            }
        }
    }

    private String findRawNode(String branchName, List<? extends BitbucketBranch> branches, TaskListener listener) {
        for (BitbucketBranch b : branches) {
            if (branchName.equals(b.getName())) {
                String revision = b.getRawNode();
                if (revision == null) {
                    if (BitbucketCloudEndpoint.SERVER_URL.equals(getServerUrl())) {
                        listener.getLogger().format("Cannot resolve the hash of the revision in branch %s%n",
                                branchName);
                    } else {
                        listener.getLogger().format("Cannot resolve the hash of the revision in branch %s. "
                                        + "Perhaps you are using Bitbucket Server previous to 4.x%n",
                                branchName);
                    }
                    return null;
                }
                return revision;
            }
        }
        listener.getLogger().format("Cannot find the branch %s%n", branchName);
        return null;
    }

    @Override
    public SCM build(SCMHead head, SCMRevision revision) {
        BitbucketRepositoryType type;
        if (head instanceof PullRequestSCMHead) {
            type = ((PullRequestSCMHead) head).getRepositoryType();
        } else if (head instanceof BranchSCMHead) {
            type = ((BranchSCMHead) head).getRepositoryType();
        } else {
            throw new IllegalArgumentException("Either PullRequestSCMHead or BranchSCMHead required as parameter");
        }
        if (type == null) {
            if (revision instanceof MercurialRevision) {
                type = BitbucketRepositoryType.MERCURIAL;
            } else if (revision instanceof SCMRevisionImpl) {
                type = BitbucketRepositoryType.GIT;
            } else {
                try {
                    type = getRepositoryType();
                } catch (IOException | InterruptedException e) {
                    type = BitbucketRepositoryType.GIT;
                    LOGGER.log(Level.SEVERE,
                            "Could not determine repository type of " + getRepoOwner() + "/" + getRepository()
                                    + " on " + getServerUrl() + " for " + getOwner() + " assuming " + type, e);
                }
            }
        }
        assert type != null;
        if (cloneLinks == null) {
            BitbucketApi bitbucket = buildBitbucketClient();
            try {
                BitbucketRepository r = bitbucket.getRepository();
                Map<String, List<BitbucketHref>> links = r.getLinks();
                if (links != null && links.containsKey("clone")) {
                    cloneLinks = links.get("clone");
                }
            } catch (IOException | InterruptedException e) {
                LOGGER.log(Level.SEVERE,
                        "Could not determine clone links of " + getRepoOwner() + "/" + getRepository()
                                + " on " + getServerUrl() + " for " + getOwner() + " falling back to generated links",
                        e);
                cloneLinks = new ArrayList<>();
                cloneLinks.add(new BitbucketHref("ssh",
                        bitbucket.getRepositoryUri(
                                type,
                                BitbucketRepositoryProtocol.SSH,
                                null,
                                getRepoOwner(),
                                getRepository()
                        )
                ));
                cloneLinks.add(new BitbucketHref("https",
                        bitbucket.getRepositoryUri(
                                type,
                                BitbucketRepositoryProtocol.HTTP,
                                null,
                                getRepoOwner(),
                                getRepository()
                        )
                ));
            }
        }
        switch (type) {
            case MERCURIAL:
                return new BitbucketHgSCMBuilder(this, head, revision, getCredentialsId())
                        .withCloneLinks(cloneLinks)
                        .withTraits(traits)
                        .build();
            case GIT:
            default:
                return new BitbucketGitSCMBuilder(this, head, revision, getCredentialsId())
                        .withCloneLinks(cloneLinks)
                        .withTraits(traits)
                        .build();

        }
    }

    @NonNull
    @Override
    public SCMRevision getTrustedRevision(@NonNull SCMRevision revision, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        if (revision instanceof PullRequestSCMRevision) {
            PullRequestSCMHead head = (PullRequestSCMHead) revision.getHead();

            try (BitbucketSCMSourceRequest request = new BitbucketSCMSourceContext(null, SCMHeadObserver.none())
                    .withTraits(traits)
                    .newRequest(this, listener)) {
                if (request.isTrusted(head)) {
                    return revision;
                }
            } catch (WrappedException wrapped) {
                wrapped.unwrap();
            }
            PullRequestSCMRevision<?> rev = (PullRequestSCMRevision) revision;
            listener.getLogger().format("Loading trusted files from base branch %s at %s rather than %s%n",
                    head.getTarget().getName(), rev.getTarget(), rev.getPull());
            return rev.getTarget();
        }
        return revision;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @CheckForNull
    /* package */ StandardUsernamePasswordCredentials credentials() {
        return BitbucketCredentials.lookupCredentials(
                getServerUrl(),
                getOwner(),
                getCredentialsId(),
                StandardUsernamePasswordCredentials.class
        );
    }

    @NonNull
    @Override
    protected List<Action> retrieveActions(@CheckForNull SCMSourceEvent event,
                                           @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        // TODO when we have support for trusted events, use the details from event if event was from trusted source
        List<Action> result = new ArrayList<>();
        final BitbucketApi bitbucket = buildBitbucketClient();
        BitbucketRepository r = bitbucket.getRepository();
        Map<String, List<BitbucketHref>> links = r.getLinks();
        if (links != null && links.containsKey("clone")) {
            cloneLinks = links.get("clone");
        }
        result.add(new BitbucketRepoMetadataAction(r));
        String defaultBranch = bitbucket.getDefaultBranch();
        if (StringUtils.isNotBlank(defaultBranch)) {
            result.add(new BitbucketDefaultBranch(repoOwner, repository, defaultBranch));
        }
        if (BitbucketCloudEndpoint.SERVER_URL.equals(getServerUrl())) {
            result.add(new BitbucketLink("icon-bitbucket-repo",
                    getServerUrl() + "/" + repoOwner + "/" + repository));
            result.add(new ObjectMetadataAction(r.getRepositoryName(), null,
                    getServerUrl() + "/" + repoOwner + "/" + repository));
        } else {
            result.add(new BitbucketLink("icon-bitbucket-repo",
                    getServerUrl() + "/projects/" + repoOwner + "/repos/" + repository));
            result.add(new ObjectMetadataAction(r.getRepositoryName(), null,
                    getServerUrl() + "/projects/" + repoOwner + "/repos/" + repository));
        }
        return result;
    }

    @NonNull
    @Override
    protected List<Action> retrieveActions(@NonNull SCMHead head,
                                           @CheckForNull SCMHeadEvent event,
                                           @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        // TODO when we have support for trusted events, use the details from event if event was from trusted source
        List<Action> result = new ArrayList<>();
        if (BitbucketCloudEndpoint.SERVER_URL.equals(getServerUrl())) {
            String branchUrl;
            String title;
            if (head instanceof PullRequestSCMHead) {
                PullRequestSCMHead pr = (PullRequestSCMHead) head;
                branchUrl = repoOwner + "/" + repository + "/pull-requests/" + pr.getId();
                title = getPullRequestTitleCache().get(pr.getId());
                ContributorMetadataAction contributor = getPullRequestContributorCache().get(pr.getId());
                if (contributor != null) {
                    result.add(contributor);
                }
            } else {
                branchUrl = repoOwner + "/" + repository + "/branch/" + Util.rawEncode(head.getName());
                title = null;
            }
            result.add(new BitbucketLink("icon-bitbucket-branch", getServerUrl() + "/" + branchUrl));
            result.add(new ObjectMetadataAction(title, null, getServerUrl() + "/" + branchUrl));
        } else {
            String branchUrl;
            String title;
            if (head instanceof PullRequestSCMHead) {
                PullRequestSCMHead pr = (PullRequestSCMHead) head;
                branchUrl = "projects/" + repoOwner + "/repos/" + repository + "/pull-requests/" +pr.getId()+"/overview";
                title = getPullRequestTitleCache().get(pr.getId());
                ContributorMetadataAction contributor = getPullRequestContributorCache().get(pr.getId());
                if (contributor != null) {
                    result.add(contributor);
                }
            } else {
                branchUrl = "projects/" + repoOwner + "/repos/" + repository + "/compare/commits"
                        + "?sourceBranch=" + URLEncoder.encode(Constants.R_HEADS + head.getName(), "UTF-8");
                title = null;
            }
            result.add(new BitbucketLink("icon-bitbucket-branch", getServerUrl() + "/" + branchUrl));
            result.add(new ObjectMetadataAction(title, null, getServerUrl()+"/"+branchUrl));
        }
        SCMSourceOwner owner = getOwner();
        if (owner instanceof Actionable) {
            for (BitbucketDefaultBranch p : ((Actionable) owner).getActions(BitbucketDefaultBranch.class)) {
                if (StringUtils.equals(getRepoOwner(), p.getRepoOwner())
                        && StringUtils.equals(repository, p.getRepository())
                        && StringUtils.equals(p.getDefaultBranch(), head.getName())) {
                    result.add(new PrimaryInstanceMetadataAction());
                    break;
                }
            }
        }
        return result;
    }

    @NonNull
    private synchronized Map<String, String> getPullRequestTitleCache() {
        if (pullRequestTitleCache == null) {
            pullRequestTitleCache = new ConcurrentHashMap<>();
        }
        return pullRequestTitleCache;
    }

    @NonNull
    private synchronized Map<String, ContributorMetadataAction> getPullRequestContributorCache() {
        if (pullRequestContributorCache == null) {
            pullRequestContributorCache = new ConcurrentHashMap<>();
        }
        return pullRequestContributorCache;
    }

    @NonNull
    public SCMHeadOrigin originOf(@NonNull String repoOwner, @NonNull String repository) {
        if (this.repository.equalsIgnoreCase(repository)) {
            if (this.repoOwner.equalsIgnoreCase(repoOwner)) {
                return SCMHeadOrigin.DEFAULT;
            }
            return new SCMHeadOrigin.Fork(repoOwner);
        }
        return new SCMHeadOrigin.Fork(repoOwner + "/" + repository);
    }

    @Symbol("bitbucket")
    @Extension
    public static class DescriptorImpl extends SCMSourceDescriptor {

        public static final String ANONYMOUS = "ANONYMOUS";
        public static final String SAME = "SAME";

        @Override
        public String getDisplayName() {
            return "Bitbucket";
        }

        public FormValidation doCheckCredentialsId(@QueryParameter String value,
                                                   @QueryParameter String bitbucketServerUrl) {
            if (!value.isEmpty()) {
                return FormValidation.ok();
            } else {
                return FormValidation.warning("Credentials are required for notifications");
            }
        }

        @Restricted(NoExternalUse.class)
        @Deprecated
        public static FormValidation doCheckBitbucketServerUrl(@QueryParameter String bitbucketServerUrl) {
            String url = Util.fixEmpty(bitbucketServerUrl);
            if (url == null) {
                return FormValidation.ok();
            }
            try {
                new URL(bitbucketServerUrl);
            } catch (MalformedURLException e) {
                return FormValidation.error("Invalid URL: " +  e.getMessage());
            }
            return FormValidation.ok();
        }

        public static FormValidation doCheckServerUrl(@QueryParameter String value) {
            if (BitbucketEndpointConfiguration.get().findEndpoint(value) == null) {
                return FormValidation.error("Unregistered Server: " + value);
            }
            return FormValidation.ok();
        }

        public boolean isServerUrlSelectable() {
            return BitbucketEndpointConfiguration.get().isEndpointSelectable();
        }

        public ListBoxModel doFillServerUrlItems() {
            return BitbucketEndpointConfiguration.get().getEndpointItems();
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath SCMSourceOwner context, @QueryParameter String serverUrl) {
            StandardListBoxModel result = new StandardListBoxModel();
            result.includeEmptyValue();
            result.includeMatchingAs(
                    context instanceof Queue.Task
                            ? Tasks.getDefaultAuthenticationOf((Queue.Task) context)
                            : ACL.SYSTEM,
                    context,
                    StandardUsernameCredentials.class,
                    URIRequirementBuilder.fromUri(serverUrl).build(),
                    CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class))
            );
            return result;
        }

        public ListBoxModel doFillRepositoryItems(@AncestorInPath SCMSourceOwner context,
                                                  @QueryParameter String serverUrl,
                                                  @QueryParameter String credentialsId,
                                                  @QueryParameter String repoOwner)
                throws IOException, InterruptedException {
            if (StringUtils.isBlank(repoOwner)) {
                return new ListBoxModel();
            }
            context.getACL().checkPermission(Item.CONFIGURE);
            serverUrl = StringUtils.defaultIfBlank(serverUrl, BitbucketCloudEndpoint.SERVER_URL);
            ListBoxModel result = new ListBoxModel();
            StandardUsernamePasswordCredentials credentials = BitbucketCredentials.lookupCredentials(
                    serverUrl,
                    context,
                    credentialsId,
                    StandardUsernamePasswordCredentials.class
            );
            try {
                BitbucketApi bitbucket = BitbucketApiFactory.newInstance(serverUrl, credentials, repoOwner, null);
                BitbucketTeam team = bitbucket.getTeam();
                List<? extends BitbucketRepository> repositories =
                        bitbucket.getRepositories(team != null ? null : UserRoleInRepository.CONTRIBUTOR);
                if (repositories.isEmpty()) {
                    throw new FillErrorResponse(Messages.BitbucketSCMSource_NoMatchingOwner(repoOwner), true);
                }
                for (BitbucketRepository repo : repositories) {
                    result.add(repo.getRepositoryName());
                }
                return result;
            } catch (FillErrorResponse | OutOfMemoryError e) {
                throw e;
            } catch (IOException e) {
                if (e instanceof BitbucketRequestException) {
                    if (((BitbucketRequestException) e).getHttpCode() == 401) {
                        throw new FillErrorResponse(credentials == null
                                ? Messages.BitbucketSCMSource_UnauthorizedAnonymous(repoOwner)
                                : Messages.BitbucketSCMSource_UnauthorizedOwner(repoOwner), true);
                    }
                } else if (e.getCause() instanceof BitbucketRequestException) {
                    if (((BitbucketRequestException) e.getCause()).getHttpCode() == 401) {
                        throw new FillErrorResponse(credentials == null
                                ? Messages.BitbucketSCMSource_UnauthorizedAnonymous(repoOwner)
                                : Messages.BitbucketSCMSource_UnauthorizedOwner(repoOwner), true);
                    }
                }
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                throw new FillErrorResponse(e.getMessage(), false);
            } catch (Throwable e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                throw new FillErrorResponse(e.getMessage(), false);
            }
        }

        @Deprecated
        @Restricted(DoNotUse.class)
        @RestrictedSince("2.2.0")
        public ListBoxModel doFillCheckoutCredentialsIdItems(@AncestorInPath SCMSourceOwner context, @QueryParameter String bitbucketServerUrl) {
            StandardListBoxModel result = new StandardListBoxModel();
            result.add("- same as scan credentials -", SAME);
            result.add("- anonymous -", ANONYMOUS);
            result.includeMatchingAs(
                    context instanceof Queue.Task
                            ? Tasks.getDefaultAuthenticationOf((Queue.Task) context)
                            : ACL.SYSTEM,
                    context,
                    StandardCredentials.class,
                    URIRequirementBuilder.fromUri(bitbucketServerUrl).build(),
                    CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardCredentials.class))
            );
            return result;
        }

        @NonNull
        @Override
        protected SCMHeadCategory[] createCategories() {
            return new SCMHeadCategory[]{
                    new UncategorizedSCMHeadCategory(Messages._BitbucketSCMSource_UncategorizedSCMHeadCategory_DisplayName()),
                    new ChangeRequestSCMHeadCategory(Messages._BitbucketSCMSource_ChangeRequestSCMHeadCategory_DisplayName())
                    // TODO add support for tags and maybe feature branch identification
            };
        }

        public List<NamedArrayList<? extends SCMSourceTraitDescriptor>> getTraitsDescriptorLists() {
            List<SCMSourceTraitDescriptor> all = new ArrayList<>();
            // all that are applicable to our context
            all.addAll(SCMSourceTrait._for(this, BitbucketSCMSourceContext.class, null));
            // all that are applicable to our builders
            all.addAll(SCMSourceTrait._for(this, null, BitbucketGitSCMBuilder.class));
            all.addAll(SCMSourceTrait._for(this, null, BitbucketHgSCMBuilder.class));
            Set<SCMSourceTraitDescriptor> dedup = new HashSet<>();
            for (Iterator<SCMSourceTraitDescriptor> iterator = all.iterator(); iterator.hasNext(); ) {
                SCMSourceTraitDescriptor d = iterator.next();
                if (dedup.contains(d)
                        || d instanceof MercurialBrowserSCMSourceTrait.DescriptorImpl
                        || d instanceof GitBrowserSCMSourceTrait.DescriptorImpl) {
                    // remove any we have seen already and ban the browser configuration as it will always be bitbucket
                    iterator.remove();
                } else {
                    dedup.add(d);
                }
            }
            List<NamedArrayList<? extends SCMSourceTraitDescriptor>> result = new ArrayList<>();
            NamedArrayList.select(all, "Within repository", NamedArrayList
                            .anyOf(NamedArrayList.withAnnotation(Discovery.class),
                                    NamedArrayList.withAnnotation(Selection.class)),
                    true, result);
            int insertionPoint = result.size();
            NamedArrayList.select(all, "Git", new NamedArrayList.Predicate<SCMSourceTraitDescriptor>() {
                @Override
                public boolean test(SCMSourceTraitDescriptor d) {
                    return GitSCM.class.isAssignableFrom(d.getScmClass());
                }
            }, true, result);
            NamedArrayList.select(all, "Mercurial", new NamedArrayList.Predicate<SCMSourceTraitDescriptor>() {
                @Override
                public boolean test(SCMSourceTraitDescriptor d) {
                    return MercurialSCM.class.isAssignableFrom(d.getScmClass());
                }
            }, true, result);
            NamedArrayList.select(all, "General", null, true, result, insertionPoint);
            return result;
        }

        public List<SCMSourceTrait> getTraitsDefaults() {
            return Arrays.asList(
                    new BranchDiscoveryTrait(true, false),
                    new OriginPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.MERGE)),
                    new ForkPullRequestDiscoveryTrait(EnumSet.of(ChangeRequestCheckoutStrategy.MERGE),
                            new ForkPullRequestDiscoveryTrait.TrustTeamForks())
            );
        }
    }

    public static class MercurialRevision extends SCMRevision {

        private static final long serialVersionUID = 1L;

        private String hash;

        public MercurialRevision(SCMHead head, String hash) {
            super(head);
            this.hash = hash;
        }

        public String getHash() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            MercurialRevision that = (MercurialRevision) o;

            return StringUtils.equals(hash, that.hash) && getHead().equals(that.getHead());

        }

        @Override
        public int hashCode() {
            return hash != null ? hash.hashCode() : 0;
        }

        @Override
        public String toString() {
            return hash;
        }

    }

    private static class CriteriaWitness implements SCMSourceRequest.Witness {
        private final BitbucketSCMSourceRequest request;

        public CriteriaWitness(BitbucketSCMSourceRequest request) {
            this.request = request;
        }

        @Override
        public void record(@NonNull SCMHead scmHead, SCMRevision revision, boolean isMatch) {
            if (revision == null) {
                request.listener().getLogger().println("    Skipped");
            } else {
                if (isMatch) {
                    request.listener().getLogger().println("    Met criteria");
                } else {
                    request.listener().getLogger().println("    Does not meet criteria");
                    return;
                }

            }
        }
    }

    private static class BitbucketProbeFactory implements SCMSourceRequest.ProbeLambda<SCMHead, String> {
        private final BitbucketApi bitbucket;
        private final BitbucketSCMSourceRequest request;

        public BitbucketProbeFactory(BitbucketApi bitbucket, BitbucketSCMSourceRequest request) {
            this.bitbucket = bitbucket;
            this.request = request;
        }

        @NonNull
        @Override
        public SCMSourceCriteria.Probe create(@NonNull final SCMHead head, @Nullable final String hash)
                throws IOException, InterruptedException {
            return new SCMSourceCriteria.Probe() {
                @Override
                public String name() {
                    return head.getName();
                }

                @Override
                public long lastModified() {
                    try {
                        BitbucketCommit commit = bitbucket.resolveCommit(hash);
                        if (commit == null) {
                            request.listener().getLogger()
                                    .format("Can not resolve commit by hash [%s] on repository %s/%s%n",
                                            hash, bitbucket.getOwner(), bitbucket.getRepositoryName());
                            return 0;
                        }
                        return commit.getDateMillis();
                    } catch (InterruptedException | IOException e) {
                        request.listener().getLogger()
                                .format("Can not resolve commit by hash [%s] on repository %s/%s%n",
                                        hash, bitbucket.getOwner(), bitbucket.getRepositoryName());
                        return 0;
                    }
                }

                @Override
                public boolean exists(@NonNull String path) throws IOException {
                    try {
                        return bitbucket.checkPathExists(hash, path);
                    } catch (InterruptedException e) {
                        throw new IOException("Interrupted", e);
                    }
                }
            };
        }
    }

    private class BitbucketRevisionFactory
            implements SCMSourceRequest.LazyRevisionLambda<SCMHead, SCMRevision, String> {
        @NonNull
        @Override
        public SCMRevision create(@NonNull SCMHead head, @Nullable String hash)
                throws IOException, InterruptedException {
            if (repositoryType == BitbucketRepositoryType.MERCURIAL) {
                return new MercurialRevision(head, hash);
            } else {
                return new SCMRevisionImpl(head, hash);
            }
        }
    }

    private static class WrappedException extends RuntimeException {

        public WrappedException(Throwable cause) {
            super(cause);
        }

        public void unwrap() throws IOException, InterruptedException {
            Throwable cause = getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof InterruptedException) {
                throw (InterruptedException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw this;
        }

    }

}
