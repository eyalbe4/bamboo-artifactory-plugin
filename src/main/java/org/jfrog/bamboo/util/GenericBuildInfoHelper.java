package org.jfrog.bamboo.util;

import com.atlassian.bamboo.util.BuildUtils;
import com.atlassian.bamboo.utils.EscapeChars;
import com.atlassian.bamboo.v2.build.BuildContext;
import com.atlassian.bamboo.v2.build.trigger.DependencyTriggerReason;
import com.atlassian.bamboo.v2.build.trigger.ManualBuildTriggerReason;
import com.atlassian.bamboo.v2.build.trigger.TriggerReason;
import com.google.common.collect.Lists;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.jfrog.bamboo.builder.BaseBuildInfoHelper;
import org.jfrog.build.api.Artifact;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildAgent;
import org.jfrog.build.api.BuildInfoFields;
import org.jfrog.build.api.BuildType;
import org.jfrog.build.api.builder.ArtifactBuilder;
import org.jfrog.build.api.builder.BuildInfoBuilder;
import org.jfrog.build.api.builder.ModuleBuilder;
import org.jfrog.build.client.DeployDetails;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @author Tomer Cohen
 */
public class GenericBuildInfoHelper extends BaseBuildInfoHelper {
    private static final Logger log = Logger.getLogger(GenericBuildInfoHelper.class);
    private final Map<String, String> env;
    private final String vcsRevision;

    public GenericBuildInfoHelper(Map<String, String> env, String vcsRevision) {
        this.env = env;
        this.vcsRevision = vcsRevision;
    }

    public void addCommonProperties(DeployDetails.Builder details) {
        details.addProperty(BuildInfoFields.BUILD_NAME, context.getPlanName());
        details.addProperty(BuildInfoFields.BUILD_NUMBER, String.valueOf(context.getBuildNumber()));
        if (StringUtils.isNotBlank(vcsRevision)) {
            details.addProperty(BuildInfoFields.VCS_REVISION, vcsRevision);
        }
        addBuildParentProperties(details, context.getTriggerReason());
    }

    public Build extractBuildInfo(BuildContext buildContext, Set<DeployDetails> details, String username) {
        String url = determineBambooBaseUrl();
        StringBuilder summaryUrl = new StringBuilder(url);
        if (!url.endsWith("/")) {
            summaryUrl.append("/");
        }
        String buildUrl = summaryUrl.append("browse/").
                append(EscapeChars.forURL(buildContext.getBuildResultKey())).toString();
        long duration =
                new Interval(new DateTime(buildContext.getBuildResult().getCustomBuildData().get("buildTimeStamp")),
                        new DateTime()).toDurationMillis();
        BuildInfoBuilder builder = new BuildInfoBuilder(buildContext.getPlanName())
                .number(String.valueOf(buildContext.getBuildNumber())).type(BuildType.GENERIC)
                .buildAgent(new BuildAgent("Bamboo", BuildUtils.getVersionAndBuild())).artifactoryPrincipal(username)
                .startedDate(new Date()).durationMillis(duration).url(buildUrl);
        if (StringUtils.isNotBlank(vcsRevision)) {
            builder.vcsRevision(vcsRevision);
        }
        List<Artifact> artifacts = convertDeployDetailsToArtifacts(details);
        ModuleBuilder moduleBuilder =
                new ModuleBuilder().id(buildContext.getPlanName() + ":" + buildContext.getBuildNumber())
                        .artifacts(artifacts);
        builder.addModule(moduleBuilder.build());
        String principal = getTriggeringUserNameRecursively(buildContext);
        if (StringUtils.isBlank(principal)) {
            principal = "auto";
        }
        builder.principal(principal);
        Map<String, String> props = filterAndGetGlobalVariables();
        props.putAll(env);
        props = TaskUtils.getEscapedEnvMap(props);
        Properties properties = new Properties();
        properties.putAll(props);
        builder.properties(properties);
        return builder.build();
    }

    private List<Artifact> convertDeployDetailsToArtifacts(Set<DeployDetails> details) {
        List<Artifact> result = Lists.newArrayList();
        for (DeployDetails detail : details) {
            String ext = FilenameUtils.getExtension(detail.getFile().getName());
            Artifact artifact = new ArtifactBuilder(detail.getFile().getName()).md5(detail.getMd5())
                    .sha1(detail.getSha1()).type(ext).build();
            result.add(artifact);
        }
        return result;
    }

    private String getTriggeringUserNameRecursively(BuildContext context) {
        String principal = null;
        TriggerReason triggerReason = context.getTriggerReason();
        if (triggerReason instanceof ManualBuildTriggerReason) {
            principal = ((ManualBuildTriggerReason) triggerReason).getUserName();

            if (StringUtils.isBlank(principal)) {

                BuildContext parentContext = context.getParentBuildContext();
                if (parentContext != null) {
                    principal = getTriggeringUserNameRecursively(parentContext);
                }
            }
        }
        return principal;
    }


    private void addBuildParentProperties(DeployDetails.Builder details, TriggerReason triggerReason) {
        if (triggerReason instanceof DependencyTriggerReason) {
            String triggeringBuildResultKey = ((DependencyTriggerReason) triggerReason).getTriggeringBuildResultKey();
            if (StringUtils.isNotBlank(triggeringBuildResultKey) &&
                    (StringUtils.split(triggeringBuildResultKey, "-").length == 3)) {
                String triggeringBuildKey =
                        triggeringBuildResultKey.substring(0, triggeringBuildResultKey.lastIndexOf("-"));
                String triggeringBuildNumber =
                        triggeringBuildResultKey.substring(triggeringBuildResultKey.lastIndexOf("-") + 1);
                String parentBuildName = getBuildName(triggeringBuildKey);
                if (StringUtils.isBlank(parentBuildName)) {
                    log.error("Received a null build parent name.");
                }
                details.addProperty(BuildInfoFields.BUILD_PARENT_NAME, parentBuildName);
                details.addProperty(BuildInfoFields.BUILD_PARENT_NUMBER, triggeringBuildNumber);
            }
        }
    }
}