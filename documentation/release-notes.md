# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.4

Vervolg op een diepgaande review van de plugin, aangevuld met de bevindingen van een
geautomatiseerde review-ronde daarop. Bevat verscheidene correctheidsfixes met direct effect op
verzonden e-mail, meerdere security-hardenings (waaronder één met een migratiestap), en de eerste
echte performance-ingreep.

**Breaking — pluginproperties verplaatst naar applicatieconfiguratie**
`tokenBaseUrl`, `graphBaseUrl`, `connectTimeoutSeconds` en `readTimeoutSeconds` zijn geen
`@PluginProperty` meer en staan nu onder `graph-mail.http` in `application.yml`. Bestaande
pluginconfiguraties met afwijkende waarden negeren die na deze upgrade — zet ze om naar de
applicatieconfiguratie. Zie `documentation/plugin.md` voor het volledige blok.

Let op: een afwijkend endpoint kan niet ongewijzigd mee — `graph-mail.http` accepteert alleen
endpoints uit een vaste Microsoft-allowlist en laat de applicatie anders falen bij opstarten.

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
  gooien. De upload volgt daarbij `nextExpectedRanges` van de server — ook wanneer die naar een
  eerdere positie wijst, want dat betekent dat Graph de zojuist verstuurde bytes níet heeft
  vastgelegd en lokaal doortellen een gat in de bijlage achterlaat. Een server die herhaaldelijk
  niet vooruit wil, of een offset buiten het bestand rapporteert, breekt de upload af als
  *transient* in plaats van een corrupte bijlage te versturen.
- De client-credentials scope volgt nu het geconfigureerde Graph-endpoint. De vaste commerciële
  scope zou elke verzending in een sovereign cloud (US Gov, China) al bij het ophalen van het token
  laten falen, terwijl de endpoint-allowlist die clouds wél accepteert.
- Een 429 of 408 van het token-endpoint wordt geretryd in plaats van gemeld als "controleer Client
  ID en Secret" — throttling van Entra is geen credentialprobleem.
- Een verbindingsfout die aantoonbaar vóór verzending optreedt (DNS, connect, TLS-handshake) geldt
  als *transient* in plaats van als onzekere uitkomst: er is niets verstuurd, dus niets te
  dupliceren.
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
  het plugin-actiepad al gebeurde — zowel in de response als in de logregel. Een
  `RestClientException` bevat de request-URI, en daar staat de afzendermailbox in het pad.
- De inline-`style`-filter weert ook CSS-escapes en -commentaar (`\75 rl(...)`, `u/**/rl(...)`), en
  verwijdert bij een treffer het hele `style`-attribuut in plaats van losse declaraties: splitsen op
  `;` is zelf omzeilbaar.

**Performance**
- De gedeelde HTTP-client staat expliciet op HTTP/1.1. De JDK-client kiest standaard HTTP/2, waardoor
  deze refactor als bijeffect ook van protocol zou wisselen — daarvóór sprak de plugin HTTP/1.1.
  Verbindingshergebruik, het hele doel hier, komt van keep-alive en werkt op 1.1 net zo goed, terwijl
  HTTP/2 een variabele toevoegt die egress-proxy's in overheidsnetwerken niet altijd goed afhandelen.
- Eén gedeelde, gepoolde HTTP-client voor alle verzendingen. Valtimo hydrateert een nieuwe
  plugin-instantie per actie, waardoor er per e-mail een complete nieuwe `RestTemplate` met eigen
  connection manager werd opgebouwd: één volledige TLS-handshake per bericht en nul hergebruik van
  verbindingen.
- `graph-mail.http.attachment-concurrency` begrenst het aantal gelijktijdige verzendingen mét
  bijlagen. Dat aantal was eerder gelijk aan de grootte van de job-executor thread-pool; bij de door
  de plugin zelf geadviseerde `max-pool-size: 50` is dat een `OutOfMemoryError`. Het slot wordt
  aangevraagd vóórdat de bijlagen worden ingelezen — anders zou elke thread eerst zijn volledige
  payload alloceren en pas daarna in de wachtrij gaan staan, en begrenst de limiet niets dat
  geheugen kost.

**Diagnostiek**
- Elke mislukte verzending logt een `verdict` (`PERMANENT_INPUT`, `PERMANENT_REMOTE`, `UNKNOWN`,
  `TRANSIENT`) plus een concrete remedie per HTTP-status, zodat een beheerder in de GZAC-logs kan
  zien of hij moet wachten of iets moet aanpassen.

## 1.0.3

Beveiligings- en betrouwbaarheidsronde bovenop 1.0.2.

**Afzenderbeperking**
- Verplichte `allowedSenders`-whitelist (deny-by-default): elke verzending — ook via procesdata
  (`pv:`) — wordt geweigerd tenzij `senderMailbox` voorkomt op een kommagescheiden lijst van
  toegestane volledige adressen en/of `@domain`-entries in de pluginconfiguratie. Geldt ook voor
  het test-send endpoint. Bestaande pluginconfiguraties weigeren na de upgrade elke verzending
  totdat de whitelist eenmalig is ingevuld en opgeslagen.

**Token-cache en secrets**
- Token-cache verplaatst naar een gedeelde `GraphTokenCache`-bean, zodat caching daadwerkelijk
  werkt over plugin-instanties heen. Valtimo hydrateert per plugin-actie een nieuwe
  `GraphMailPlugin`, waardoor een instance-eigen cache nooit hits opbouwde.
- De cache-key bestond alleen uit `tenantId:clientId`, waardoor een pluginconfiguratie met een
  verkeerd of verouderd `clientSecret` een token kon hergebruiken dat een andere, correcte
  configuratie voor dezelfde tenant/client al had opgehaald — zonder dat het secret opnieuw bij
  Azure Entra werd geverifieerd. De key bevat nu een hash van het `clientSecret`.
- `GraphCredentials`, `TokenResponse` en de interne `CachedToken` hadden geen eigen `toString()`,
  waardoor Kotlin's gegenereerde versie het `clientSecret` respectievelijk het access-token in
  cleartext zou tonen zodra zo'n object gelogd of geprint werd. `toString()` maskeert nu altijd.
- Afzenderadres gemaskeerd in het `GraphMailEmailSentEvent` van het test-send endpoint — de
  reguliere verzendactie deed dit al, het test-send pad publiceerde het volledige adres.

**Dubbele verzending bij transactieretry**
- Nieuwe `SendIdempotencyGuard`: als de Operaton-transactie na een geslaagde verzending alsnog
  terugrolt (bijv. door een optimistic lock op andere procesdata) en de service-task-activiteit
  opnieuw uitvoert, werd de e-mail voorheen een tweede keer verstuurd — Graph had het eerste
  verzoek al onomkeerbaar geaccepteerd. Een niet-transactionele, in-memory guard herkent deze
  herhaling (execution-id + activity-id) en slaat de tweede Graph-aanroep over. Beperking: dit
  beschermt tegen een retry binnen dezelfde JVM-instantie, niet tegen een applicatie-herstart
  tussen de oorspronkelijke verzending en een latere retry.
- De check is atomair (per-sleutel `ReentrantLock`) en gebeurt vóór het dure werk (validatie,
  body-sanitisatie, bijlagen inlezen), zodat een gedetecteerde retry niets onnodig uitvoert en
  twee gelijktijdige retries elkaar niet kunnen passeren.
- Datzelfde per-sleutel lock-patroon bevatte een race: een lock kon geëvicteerd worden tussen het
  ophalen van de referentie en het daadwerkelijk locken, waarna een volgende caller een ander
  Lock-object kreeg en parallel doorliep. Zowel `SendIdempotencyGuard` als `GraphTokenCache`
  locken nu eerst en valideren daarna of de lock nog de actuele map-entry is.

**Retry-logica**
- Vier bijna-identieke, met de hand geschreven retry-loops (draft aanmaken, upload-sessie
  aanmaken, draft versturen, inline versturen) samengevoegd tot één gedeelde implementatie.
  Daarbij zijn twee robuustheidsgaten gedicht: de draft-flow retryde nooit op netwerkfouten
  (in tegenstelling tot het inline-verzendpad), en het aanmaken van een upload-sessie retryde
  helemaal niet op 429/5xx en gaf bij een tweede 401 de verkeerde exception-klasse terug.

**Test-send endpoint**
- De rate-limiter hield voor elke gebruiker die ooit een testmail verstuurde blijvend een entry
  aan in een store die nooit kromp. Verouderde entries worden nu opgeruimd zodra de store een
  omvangsdrempel overschrijdt.
- Bug gefixt waarbij de test-mail footer letterlijk `$escapedSender` toonde in plaats van het
  gebruikte afzenderadres.

**Overig**
- `jsoup` opgehoogd van 1.17.2 naar 1.23.1 (CVE-2026-71497, Cleaner XSS-bypass via een misvormde
  tagnaam). De `EMAIL_HTML_SAFELIST` van deze plugin voegt geen raw-text-elementen toe en was dus
  niet daadwerkelijk kwetsbaar, maar dit is de bibliotheek waar de HTML-sanitisatie op leunt.
- Script-injectie in `publish-backend.yaml` / `publish-frontend.yaml` gedicht: `${{ }}`-waarden
  worden nu via `env:` als quoted shell-variabele gelezen in plaats van direct in het `run:`-script
  geïnterpoleerd. `tj-actions/changed-files` gepind op een commit-SHA in plaats van de mutable tag
  `v45` (die in maart 2025 gecompromitteerd is, CVE-2025-30066).
- `cn.lalaki.central` stond als `implementation`-dependency in de subprojects-block en belandde
  daardoor als runtime-dependency in de gepubliceerde POM — elke consument trok een
  Gradle-publicatieplugin binnen. Verwijderd.
- `GraphMailClient.sendMail` vereenvoudigd van 12 losse parameters naar de
  `GraphCredentials`/`OutboundMail`-parameterobjecten.
- Frontend bouwt API-URL's via `ConfigService` in plaats van hardcoded paden, zodat de plugin ook
  werkt wanneer frontend en backend op verschillende origins draaien.
- Blokkerende ktlint-check toegevoegd aan de PR-checks, en Dependabot geconfigureerd voor gradle,
  npm en github-actions.

## 1.0.2

Valtimo bijgewerkt naar versie 13.41.0.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
