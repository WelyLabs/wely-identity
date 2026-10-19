<#--
  The foot of the admin login card: who may sign in here, and nothing else.

  Overrides calendar-app's footer.ftl, which shows the public demo account: those credentials
  sign in to the application, never to the admin console, and must not be offered here.
-->
<#macro content>
  <#if (pageId!'') == 'login'>
    <div class="wely-foot">
      <p class="wely-secure">
        <span class="wely-dot" aria-hidden="true"></span>${msg("welyAdminFooter")}
      </p>
    </div>
  </#if>
</#macro>
