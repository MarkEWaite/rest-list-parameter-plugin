package io.jenkins.plugins.restlistparam;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.SimpleParameterDefinition;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.restlistparam.logic.RestValueService;
import io.jenkins.plugins.restlistparam.model.MimeType;
import io.jenkins.plugins.restlistparam.model.ResultContainer;
import io.jenkins.plugins.restlistparam.util.CredentialsUtils;
import io.jenkins.plugins.restlistparam.util.PathExpressionValidationUtils;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

public class RestListParameterDefinition extends SimpleParameterDefinition {
  private static final long serialVersionUID = 3453376762337829455L;

  private final String restEndpoint;
  private final String credentialId;
  private final MimeType mimeType;
  private final String valueExpression;
  private String defaultValue;
  private String filter;
  private String errorMsg;
  private Collection<String> values;

  @DataBoundConstructor
  public RestListParameterDefinition(final String name,
                                     final String description,
                                     final String restEndpoint,
                                     final String credentialId,
                                     final MimeType mimeType,
                                     final String valueExpression)
  {
    this(name, description, restEndpoint, credentialId, mimeType, valueExpression, ".*", "");
  }

  public RestListParameterDefinition(final String name,
                                     final String description,
                                     final String restEndpoint,
                                     final String credentialId,
                                     final MimeType mimeType,
                                     final String valueExpression,
                                     final String filter,
                                     final String defaultValue)
  {
    super(name, description);
    this.restEndpoint = restEndpoint;
    this.mimeType = mimeType;
    this.valueExpression = valueExpression;
    this.credentialId = StringUtils.isNotBlank(credentialId) ? credentialId : "";
    this.defaultValue = StringUtils.isNotBlank(defaultValue) ? defaultValue : "";
    this.filter = StringUtils.isNotBlank(filter) ? filter : ".*";
    this.errorMsg = "";
    this.values = Collections.emptyList();
  }

  public String getRestEndpoint() {
    return restEndpoint;
  }

  public String getCredentialId() {
    return credentialId;
  }

  public MimeType getMimeType() {
    return mimeType;
  }

  public String getValueExpression() {
    return valueExpression;
  }

  public String getFilter() {
    return filter;
  }

  @DataBoundSetter
  public void setFilter(final String filter) {
    this.filter = filter;
  }

  public String getDefaultValue() {
    return defaultValue;
  }

  @DataBoundSetter
  public void setDefaultValue(final String defaultValue) {
    this.defaultValue = defaultValue;
  }

  void setErrorMsg(final String errorMsg) {
    this.errorMsg = errorMsg;
  }

  public String getErrorMsg() {
    return errorMsg;
  }

  public Collection<String> getValues() {
    if (values == null || values.isEmpty()) {
      Optional<StandardCredentials> credentials = CredentialsUtils.findCredentials(credentialId);

      ResultContainer<Collection<String>> container = RestValueService.get(
        restEndpoint,
        credentials.orElse(null),
        mimeType,
        valueExpression,
        filter);

      setErrorMsg(container.getErrorMsg().orElse(""));
      values = container.getValue();
    }
    return values;
  }

  @Override
  public ParameterDefinition copyWithDefaultValue(final ParameterValue defaultValue) {
    if (defaultValue instanceof RestListParameterValue) {
      RestListParameterValue value = (RestListParameterValue) defaultValue;
      return new RestListParameterDefinition(
        getName(), getDescription(), getRestEndpoint(), getCredentialId(),
        getMimeType(), getValueExpression(), getFilter(), value.getValue());
    }
    else {
      return this;
    }
  }

  @Override
  public ParameterValue createValue(final String value) {
    RestListParameterValue parameterValue = new RestListParameterValue(getName(), value, getDescription());
    checkValue(parameterValue);
    return parameterValue;
  }

  @Override
  @CheckForNull
  public ParameterValue createValue(final StaplerRequest req,
                                    final JSONObject jo)
  {
    RestListParameterValue value = req.bindJSON(RestListParameterValue.class, jo);
    checkValue(value);
    return value;
  }

  private void checkValue(final RestListParameterValue value) {
    if (!isValid(value)) {
      throw new IllegalArgumentException(Messages.RLP_Definition_ValueException(getName(), value.getValue()));
    }
  }

  public boolean isValid(ParameterValue value) {
    return values.contains(((RestListParameterValue) value).getValue());
  }

  @Symbol({"RESTList", "RestList", "RESTListParam"})
  @Extension
  public static class DescriptorImpl extends ParameterDescriptor {
    @Override
    @Nonnull
    public String getDisplayName() {
      return Messages.RLP_DescriptorImpl_DisplayName();
    }

    @POST
    public FormValidation doCheckRestEndpoint(@QueryParameter final String value,
                                              @AncestorInPath final Item context)
    {
      if (context == null) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      }
      else {
        context.checkPermission(Item.CONFIGURE);
      }

      if (StringUtils.isNotBlank(value)) {
        if (value.matches("^http(s)?://.+")) {
          return FormValidation.ok();
        }
        return FormValidation.error(Messages.RLP_DescriptorImpl_ValidationErr_EndpointUrl());
      }
      return FormValidation.error(Messages.RLP_DescriptorImpl_ValidationErr_EndpointEmpty());
    }

    @POST
    public FormValidation doCheckValueExpression(@QueryParameter final String value,
                                                 @QueryParameter final MimeType mimeType,
                                                 @AncestorInPath final Item context)
    {
      if (context == null) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      }
      else {
        context.checkPermission(Item.CONFIGURE);
      }

      if (StringUtils.isNotBlank(value)) {
        switch (mimeType) {
          case APPLICATION_JSON:
            return PathExpressionValidationUtils.doCheckJsonPathExpression(value);
          case APPLICATION_XML:
            return PathExpressionValidationUtils.doCheckXPathExpression(value);
          default:
            return FormValidation.error(Messages.RLP_DescriptorImpl_ValidationErr_UnknownMime());
        }
      }
      return FormValidation.error(Messages.RLP_DescriptorImpl_ValidationErr_ExpressionEmpty());
    }

    public ListBoxModel doFillCredentialIdItems(@AncestorInPath final Item context,
                                                @QueryParameter final String credentialId)
    {
      return CredentialsUtils.doFillCredentialsIdItems(context, credentialId);
    }

    public FormValidation doCheckCredentialId(@QueryParameter final String value,
                                              @AncestorInPath final Item context)
    {
      if (context == null) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
          return FormValidation.ok();
        }
      }
      else {
        if (!context.hasPermission(Item.EXTENDED_READ)
          && !context.hasPermission(CredentialsProvider.USE_ITEM))
        {
          return FormValidation.ok();
        }
      }

      return CredentialsUtils.doCheckCredentialsId(value);
    }
  }
}