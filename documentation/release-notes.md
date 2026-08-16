# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- Publish-pipeline naar Maven Central gefixt: `cn.lalaki.central` werd alleen op het rootproject
  geregistreerd en niet op het `plugin`-subproject, waardoor `publishToCentralPortal` niet bestond
  voor het daadwerkelijk te publiceren artefact.
- Verplichte `allowedSenders`-whitelist toegevoegd (deny-by-default): elke verzending — ook via
  proces­data (`pv:`) — wordt geweigerd tenzij `senderMailbox` voorkomt op een kommagescheiden lijst
  van toegestane volledige adressen en/of `@domain`-entries in de pluginconfiguratie. Geldt ook voor
  het test-send endpoint. Bestaande pluginconfiguraties weigeren na de upgrade elke verzending totdat
  de whitelist eenmalig is ingevuld en opgeslagen.
- Token-cache verplaatst naar een gedeelde `GraphTokenCache`-bean, zodat caching daadwerkelijk werkt
  over plugin-instanties heen. Valtimo hydrateert per plugin-actie een nieuwe `GraphMailPlugin` (en
  dus voorheen een nieuwe `GraphMailClientImpl` met een eigen cache), waardoor een instance-eigen
  cache nooit hits opbouwde.
- `GraphMailClient.sendMail`-interface vereenvoudigd van 12 losse parameters naar de
  `GraphCredentials`/`OutboundMail`-parameterobjecten. Geen gedragswijziging in retry/backoff,
  draft+upload of foutafhandeling.
- Bug gefixt in de test-mail footer: door een verkeerd gebruikte escape-sequence (`${'$'}escapedSender`
  in plaats van gewone interpolatie) toonde elke testmail letterlijk de tekst `$escapedSender` in
  plaats van het daadwerkelijk gebruikte afzenderadres.
- Beveiligingsfix in de gedeelde token-cache: de cache-key bestond alleen uit `tenantId:clientId`,
  waardoor een pluginconfiguratie met een verkeerd of verouderd `clientSecret` een token kon
  hergebruiken dat een andere, correcte configuratie voor dezelfde tenant/client al had opgehaald
  en gecachet — zonder dat het secret opnieuw bij Azure Entra werd geverifieerd. De cache-key bevat
  nu een hash van het `clientSecret`, zodat een ander secret altijd een cache-miss geeft.
- `GraphCredentials` had geen eigen `toString()`, waardoor Kotlin's automatisch gegenereerde versie
  het `clientSecret` in cleartext zou tonen zodra het object ooit gelogd of geprint werd (bijv. een
  debug-logregel of een mislukte testassertion). `toString()` maskeert het secret nu altijd.
- Afzenderadres gemaskeerd in het `GraphMailEmailSentEvent` dat het test-send endpoint publiceert
  — was hier per ongeluk nog ongemaskeerd, terwijl de reguliere verzendactie dit al consequent deed.
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
  testmail heeft verstuurd blijvend een entry aan in een in-memory store die nooit kromp — op een
  langlopende instantie met veel verschillende admins groeide deze onbegrensd. Verouderde entries
  (buiten het rate-limit-venster) worden nu periodiek opgeruimd zodra de store een omvangsdrempel
  overschrijdt.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
