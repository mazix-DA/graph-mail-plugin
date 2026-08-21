# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

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
