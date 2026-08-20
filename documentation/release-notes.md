# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.5

Vervolg op een diepgaande review van de plugin. Bevat twee correctheidsfixes met direct effect op
verzonden e-mail, een security-hardening met een migratiestap, en de eerste echte
performance-ingreep.

**Breaking — pluginproperties verplaatst naar applicatieconfiguratie**
`tokenBaseUrl`, `graphBaseUrl`, `connectTimeoutSeconds` en `readTimeoutSeconds` zijn geen
`@PluginProperty` meer en staan nu onder `graph-mail.http` in `application.yml`. Bestaande
pluginconfiguraties met afwijkende waarden negeren die na deze upgrade — zet ze om naar de
applicatieconfiguratie. Zie `documentation/plugin.md` voor het volledige blok.

Reden: het client secret wordt als formulierveld naar `tokenBaseUrl` gePOST. Zolang die property
vanuit de beheer-UI instelbaar was, kon iedereen die pluginconfiguraties mocht beheren het secret
naar een eigen host laten sturen. `graphBaseUrl` gaf bovendien een SSRF-primitief, en de
hostvalidatie van de upload-URL leidde haar verwachte host áf uit `graphBaseUrl` — waardoor die
controle in productie precies zo sterk was als de waarde die een beheerder invulde. De endpoints
worden nu bij opstarten gevalideerd tegen een vaste allowlist van Microsoft-endpoints.

**Correctheid**
- Loop over dezelfde service task verstuurde alleen de eerste e-mail. De duplicaat-guard sleutelde
  op `execution.id` + `currentActivityId`; een flow die terugloopt naar dezelfde task hergebruikt
  beide waarden, waardoor elke volgende iteratie 30 minuten lang stil werd overgeslagen — het
  proces liep door alsof de mail verstuurd was. De sleutel is nu de activity-*instantie*, die wel
  stabiel is over een job-executor retry maar uniek per iteratie.
- Transportfouten op een verzendaanroep worden niet meer automatisch opnieuw geprobeerd. Een
  read-timeout zegt niets over of Graph het bericht al accepteerde; de retry (tot 5×) kon de
  ontvanger meerdere kopieën bezorgen. Conceptaanmaak en upload-sessies zijn wél herhaalbaar en
  blijven retryen. De onzekere uitkomst is herkenbaar als `verdict=UNKNOWN` in de auditlog.
- De 401-refresh garandeert nu een daadwerkelijk nieuw token. Invalideren-en-opnieuw-lezen kon
  hetzelfde geweigerde token terugkrijgen als een andere thread het net had teruggeschreven,
  waarna de fout werd gerapporteerd als een ontbrekende `Mail.Send`-permissie.
- Opruimen van een concept gebeurt niet meer ná de verzendaanroep. Een geslaagde verzending die op
  het antwoord time-oute liet de opruimactie het bericht uit Verzonden items verwijderen.
- 429 tijdens een chunk-upload wordt nu geretryd in plaats van de hele upload en het concept weg te
  gooien; de upload volgt daarbij `nextExpectedRanges` van de server in plaats van blind door te
  tellen op de eigen offset.
- De token-fetch respecteert nu de deadline van de verzending. De "harde" wandkloklimiet van 30s
  kon eerder met tientallen seconden overschreden worden door de eigen retry-loop van de
  token-aanvraag.

**Security**
- Binnen toegestane inline `style`-attributen worden `url(...)`, `@import`, `expression(...)` en
  `javascript:` weggefilterd. `<style>`-blokken werden geweerd juist omdát die externe requests
  kunnen triggeren, terwijl `style="background:url(...)"` dezelfde tracking-pixel alsnog toeliet.
- Het test-send endpoint geeft een Graph-401 niet langer door als HTTP 401 — dat liet de
  auth-interceptor van de frontend de beheerder uitloggen bij niets meer dan een verkeerd getypt
  client secret. Het wordt nu een 502; de Graph-status blijft in de response body staan.
- Ruwe exception-teksten uit het test-send endpoint worden gemaskeerd op e-mailadressen, net als op
  het plugin-actiepad al gebeurde.

**Performance**
- Eén gedeelde, gepoolde HTTP-client voor alle verzendingen. Valtimo hydrateert een nieuwe
  plugin-instantie per actie, waardoor er per e-mail een complete nieuwe `RestTemplate` met eigen
  connection manager werd opgebouwd: één volledige TLS-handshake per bericht en nul hergebruik van
  verbindingen.
- `graph-mail.http.attachment-concurrency` begrenst het aantal gelijktijdige verzendingen mét
  bijlagen. Dat aantal was eerder gelijk aan de grootte van de job-executor thread-pool; bij de door
  de plugin zelf geadviseerde `max-pool-size: 50` is dat een `OutOfMemoryError`.

**Diagnostiek**
- Elke mislukte verzending logt een `verdict` (`PERMANENT_INPUT`, `PERMANENT_REMOTE`, `UNKNOWN`,
  `TRANSIENT`) plus een concrete remedie per HTTP-status, zodat een beheerder in de GZAC-logs kan
  zien of hij moet wachten of iets moet aanpassen.

## 1.0.4
Bescherming tegen dubbele e-mailverzending bij een Operaton-transactieretry, inclusief twee
rondes correcties op de concurrency-correctheid van die bescherming zelf.
- Vier bijna-identieke, met de hand geschreven retry-loops (draft aanmaken, upload-sessie
  aanmaken, draft versturen, inline versturen) samengevoegd tot één gedeelde implementatie.
  Daarbij zijn twee robuustheidsgaten gedicht die de duplicatie camoufleerde: de draft-flow
  retryde nooit op netwerkfouten (in tegenstelling tot het inline-verzendpad), en het aanmaken
  van een upload-sessie retryde helemaal niet op 429/5xx en gaf bij een tweede 401 de verkeerde
  exception-klasse terug.
- Nieuwe `SendIdempotencyGuard`: als de Operaton-transactie na een geslaagde verzending alsnog
  terugrolt (bijv. door een optimistic lock op andere procesdata) en de service-task-activiteit
  daardoor opnieuw uitvoert, werd de e-mail voorheen een tweede keer verstuurd — Graph had het
  eerste verzoek al onomkeerbaar geaccepteerd. Een niet-transactionele, in-memory guard herkent
  deze exacte herhaling (execution-id + activity-id) en slaat de tweede Graph-aanroep over.
  Beperking: dit beschermt tegen een retry binnen dezelfde JVM-instantie (het realistische
  scenario), niet tegen een applicatie-herstart tussen de oorspronkelijke verzending en een latere
  retry — dat zou een duurzame, transactie-onafhankelijke opslag vereisen.
- Het eerste ontwerp van de guard bleek zelf nog een prestatie- en een concurrency-gebrek te
  hebben: de duplicate-check gebeurde pas vlak vóór de daadwerkelijke Graph-aanroep, ná validatie,
  body-sanitisatie en het inlezen van bijlagen — bij een gedetecteerde retry was al dat werk voor
  niets gedaan. En de check ("is dit al verstuurd?") en het markeren als verstuurd waren twee
  aparte, niet-atomaire stappen, een reëel race window waarin twee gelijktijdige retries allebei
  de check konden doorstaan en dus alsnog beide de e-mail konden versturen. Opgelost met een
  goedkope check vóór het dure werk, en een atomaire `ifNotAlreadySent` (per-sleutel
  `ReentrantLock`, hetzelfde patroon als de bestaande `GraphTokenCache`) als autoritatieve check.
- Een externe review wees vervolgens op een subtiele race in dat per-sleutel lock-patroon zelf
  (zowel in `SendIdempotencyGuard` als in de bestaande `GraphTokenCache`): een lock kon in een
  smal venster geëvicteerd worden tussen het moment dat een caller de referentie ophaalt en het
  moment dat 'm daadwerkelijk lockt — een derde caller voor dezelfde sleutel kreeg dan een
  gloednieuw, ander Lock-object en liep parallel met de eerste door, precies de dubbele uitvoering
  die deze klassen moeten voorkomen. Beide klassen locken nu eerst en valideren daarna of de lock
  nog de actuele instantie in de map is; zo niet, dan wordt de wees-lock losgelaten en opnieuw
  geprobeerd. De lock-eviction-scan is ook begrensd, zodat een map vol sleutels die allemaal nog
  legitiem in gebruik zijn niet bij elke aanroep opnieuw volledig doorzocht wordt.
- Nieuwe testdekking: een concurrency-test die de race met twee threads en een gecontroleerde
  `CountDownLatch`-vrijgave simuleert, en `GraphTokenCacheTest` (voorheen geen dekking) voor
  cache-hits, het samenvallen van gelijktijdige cache-misses in één fetch, en prefix-invalidatie.

## 1.0.3
Dependency-hygiëne en verdere secret-maskering.
- `jsoup` opgehoogd van 1.17.2 naar 1.23.1. 1.17.2 valt binnen het kwetsbare bereik van
  CVE-2026-71497 (Cleaner XSS-bypass via een misvormde tagnaam die eindigt op een controlekarakter,
  alleen uitbuitbaar bij een custom Safelist die raw-text-elementen toestaat). De `EMAIL_HTML_SAFELIST`
  van deze plugin voegt geen raw-text-elementen toe en was dus niet daadwerkelijk kwetsbaar, maar
  aangezien dit de bibliotheek is waar de HTML-sanitisatie van de plugin op leunt, is defensief
  opgehoogd naar de gepatchte versie.
- `TokenResponse` en `GraphTokenCache`'s interne `CachedToken` hadden geen eigen `toString()`,
  waardoor Kotlin's automatisch gegenereerde versie het Graph API access-token in cleartext zou
  tonen zodra een van beide objecten ooit gelogd of geprint werd. Dezelfde klasse kwetsbaarheid als
  eerder gefixt voor `GraphCredentials` (zie 1.0.2), maar dan voor het token zelf — een direct
  bruikbaar credential voor de volledige geldigheidsduur van de token. `toString()` maskeert het
  token nu altijd.

## 1.0.2
Beveiligingshardening van de CI/CD-pipeline en de gedeelde token-cache, plus een
betrouwbaarheidsfix in de test-send endpoint.
- De GitHub Actions workflows `publish-backend.yaml` en `publish-frontend.yaml` interpoleerden
  `${{ matrix.value }}` en step-outputs rechtstreeks in `run:`-scripts. Omdat `${{ }}` als platte
  tekst wordt gesubstitueerd vóórdat de shell (of, in het frontend-script, Node.js) het script
  parseert, was dit een script-injectiepad (shell- en JS-string-injectie) via directorynamen uit de
  changed-files diff. Alle interpolaties gaan nu via `env:` en worden als quoted shell-variabele
  gelezen; het `node -e` script in de frontend-publicatie leest `process.env.CHANGED_DIR` in plaats
  van de waarde in de JS-broncode te splitsen. `tj-actions/changed-files` was ook gepind op een
  mutable tag (`v45`) — deze action's tags zijn in maart 2025 gecompromitteerd (CVE-2025-30066) om
  CI-secrets in workflow-logs te dumpen — nu gepind op de actuele, geverifieerde commit-SHA.
- De cache-key voor de gedeelde `GraphTokenCache` bestond alleen uit `tenantId:clientId`, waardoor een
  pluginconfiguratie met een verkeerd of verouderd `clientSecret` een token kon hergebruiken dat een
  andere, correcte configuratie voor dezelfde tenant/client al had opgehaald en gecachet — zonder dat
  het secret opnieuw bij Azure Entra werd geverifieerd. De cache-key bevat nu een hash van het
  `clientSecret`, zodat een ander secret altijd een cache-miss geeft. `GraphCredentials` had ook geen
  eigen `toString()`, waardoor Kotlin's automatisch gegenereerde versie het `clientSecret` in
  cleartext zou tonen zodra het object ooit gelogd of geprint werd — `toString()` maskeert het
  secret nu altijd.
- De rate-limiter van `/api/v1/plugin/entra/test-send` hield voor elke gebruiker die ooit een
  testmail heeft verstuurd blijvend een entry aan — de in-memory store groeide onbegrensd op een
  langlopende instantie. Verouderde entries (buiten het rate-limit-venster) worden nu periodiek
  opgeruimd zodra de store een omvangsdrempel overschrijdt.

## 1.0.1
Architectuur- en betrouwbaarheidsverbeteringen, plus documentatiecorrecties.
- Token-cache verplaatst naar een gedeelde `GraphTokenCache`-bean, zodat caching daadwerkelijk werkt over
  plugin-instanties heen in plaats van per instantie opnieuw te beginnen.
- `GraphMailClient.sendMail`-interface vereenvoudigd naar `GraphCredentials`/`OutboundMail`-parameterobjecten.
- Frontend bouwt API-URL's nu via `ConfigService` in plaats van hardcoded paden, zodat de plugin blijft werken
  wanneer frontend en backend op verschillende origins draaien.
- Frontend-testinfrastructuur van de pluginlibrary hersteld (stond voorheen niet aangesloten).
- Publish-pipeline naar Maven Central gefixt (`cn.lalaki.central` correct toegepast op het pluginsubproject).
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
