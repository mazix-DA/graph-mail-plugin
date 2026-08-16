# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.5
Laatste ronde van de grondige security-analyse: dependency-hygiëne en verdere secret-maskering.
- `jsoup` opgehoogd van 1.17.2 naar 1.23.1. 1.17.2 valt binnen het kwetsbare bereik van
  CVE-2026-71497 (Cleaner XSS-bypass via een misvormde tagnaam die eindigt op een controlekarakter,
  alleen uitbuitbaar bij een custom Safelist die raw-text-elementen toestaat). De `EMAIL_HTML_SAFELIST`
  van deze plugin voegt geen raw-text-elementen toe en was dus niet daadwerkelijk kwetsbaar, maar
  aangezien dit de bibliotheek is waar de HTML-sanitisatie van de plugin op leunt, is defensief
  opgehoogd naar de gepatchte versie.
- `TokenResponse` en `GraphTokenCache`'s interne `CachedToken` hadden geen eigen `toString()`,
  waardoor Kotlin's automatisch gegenereerde versie het Graph API access-token in cleartext zou
  tonen zodra een van beide objecten ooit gelogd of geprint werd. Dezelfde klasse kwetsbaarheid als
  eerder gefixt voor `GraphCredentials` (1.0.3), maar dan voor het token zelf — een direct bruikbaar
  credential voor de volledige geldigheidsduur van de token. `toString()` maskeert het token nu altijd.

## 1.0.4
Beveiligingshardening van de CI/CD-pipeline en een betrouwbaarheidsfix in de test-send endpoint.
- De GitHub Actions workflows `publish-backend.yaml` en `publish-frontend.yaml` interpoleerden
  `${{ matrix.value }}` en step-outputs rechtstreeks in `run:`-scripts. Omdat `${{ }}` als platte
  tekst wordt gesubstitueerd vóórdat de shell (of, in het frontend-script, Node.js) het script
  parseert, was dit een script-injectiepad (shell- en JS-string-injectie) via directorynamen uit de
  changed-files diff. Alle interpolaties gaan nu via `env:` en worden als quoted shell-variabele
  gelezen; het `node -e` script in de frontend-publicatie leest `process.env.CHANGED_DIR` in plaats
  van de waarde in de JS-broncode te splitsen.
- `tj-actions/changed-files` was gepind op een mutable tag (`v45`). Deze action's tags zijn in maart
  2025 gecompromitteerd (CVE-2025-30066) om CI-secrets in workflow-logs te dumpen. Gepind op de
  actuele, geverifieerde commit-SHA zodat een toekomstige tag-herpointing niet stilzwijgend andere
  code kan uitvoeren in een pipeline die de Sonatype- en npm-publicatiesecrets gebruikt.
- De rate-limiter van `/api/v1/plugin/entra/test-send` hield voor elke gebruiker die ooit een
  testmail heeft verstuurd blijvend een entry aan — de in-memory store groeide onbegrensd op een
  langlopende instantie. Verouderde entries (buiten het rate-limit-venster) worden nu periodiek
  opgeruimd zodra de store een omvangsdrempel overschrijdt.

## 1.0.3
Beveiligingsfixes in de gedeelde token-cache die in 1.0.2 werd geïntroduceerd.
- De cache-key voor de gedeelde `GraphTokenCache` bestond alleen uit `tenantId:clientId`, waardoor een
  pluginconfiguratie met een verkeerd of verouderd `clientSecret` een token kon hergebruiken dat een
  andere, correcte configuratie voor dezelfde tenant/client al had opgehaald en gecachet — zonder dat
  het secret opnieuw bij Azure Entra werd geverifieerd. De cache-key bevat nu een hash van het
  `clientSecret`, zodat een ander secret altijd een cache-miss geeft.
- `GraphCredentials` had geen eigen `toString()`, waardoor Kotlin's automatisch gegenereerde versie het
  `clientSecret` in cleartext zou tonen zodra het object ooit gelogd of geprint werd (bijv. een
  debug-logregel of een mislukte testassertion). `toString()` maskeert het secret nu altijd.

## 1.0.2
Vervolg op de security-hardening: architectuur- en betrouwbaarheidsverbeteringen.
- Token-cache verplaatst naar een gedeelde `GraphTokenCache`-bean, zodat caching daadwerkelijk werkt over
  plugin-instanties heen in plaats van per instantie opnieuw te beginnen.
- `GraphMailClient.sendMail`-interface vereenvoudigd naar `GraphCredentials`/`OutboundMail`-parameterobjecten.
- Frontend bouwt API-URL's nu via `ConfigService` in plaats van hardcoded paden, zodat de plugin blijft werken
  wanneer frontend en backend op verschillende origins draaien.
- Frontend-testinfrastructuur van de pluginlibrary hersteld (stond voorheen niet aangesloten).
- Publish-pipeline naar Maven Central gefixt (`cn.lalaki.central` correct toegepast op het pluginsubproject).

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.
- Codecommentaar bij de `send-email`-actie gecorrigeerd: deze vuurt op `SERVICE_TASK_START`, niet op
  `USER_TASK_CREATE` — en een procesvariabele is geen betrouwbare dedupe-guard bij een transactieretry,
  omdat die transactioneel is en samen met de retry terugrolt.
- Bijlagedrempel in de documentatie gecorrigeerd van 3 MB naar de daadwerkelijke 2 MiB.
- Toegevoegd: dat `Mail.Send`/`Mail.ReadWrite` applicatiemachtigingen zijn die alleen door een tenant-/Entra-beheerder
  met admin consent kunnen worden toegekend.
- Toegevoegd: een waarschuwing over `queue-size` bij de job-executor-configuratie — threads boven
  `core-pool-size` worden pas aangemaakt zodra de wachtrij vol is, dus een hoge `queue-size` maakt
  `max-pool-size` in de praktijk krachteloos.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
Inclusief verplichte `allowedSenders`-whitelist (deny-by-default): procesdata of procesbouwers kunnen geen
afzender kiezen die niet expliciet is toegestaan in de pluginconfiguratie.
