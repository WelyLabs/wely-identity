<#--
  The foot of the login card.

  An extension point, not a copied template: the parent's footer.ftl is an empty macro left
  there to be overridden, so there is no Keycloak logic to port at the next upgrade.

  Markup rather than a CSS "content:", because it carries information: where the user is
  typing their password, and the public demo credentials. A screen reader must announce
  both. The dot says nothing the sentence does not, hence aria-hidden.

  Login page only: footer.ftl is rendered by every page of the theme, and a hint about how
  to sign in has nothing to do under an error page.
-->
<#macro content>
  <#if (pageId!'') == 'login'>
    <div class="wely-foot">
      <p class="wely-demo">
        <span class="wely-demo-label">${msg("welyDemoLabel")}</span>
        <span class="wely-demo-credentials">${msg("welyDemoCredentials")}</span>
      </p>
      <p class="wely-secure">
        <span class="wely-dot" aria-hidden="true"></span>${msg("welySecureFooter")}
      </p>
    </div>
  </#if>
</#macro>
