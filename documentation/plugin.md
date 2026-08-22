# Graph Mail Plugin Documentatie

Verstuur e-mails via de Microsoft Graph API met OAuth2 (Client Credentials flow).

## Vereisten

Een **Azure App Registration** met de volgende instellingen:

- Applicatiemachtiging: `Mail.Send` (niet delegated) — vereist voor alle e-mailverzendingen
- Applicatiemachtiging: `Mail.ReadWrite` (niet delegated) — **alleen** vereist zodra één losse bijlage óf het totaal van alle bijlagen samen groter is dan 2 MiB; de plugin maakt dan eerst een conceptbericht aan via de Graph API upload-sessie flow
- Een client secret aangemaakt onder *Certificates & secrets*

> **Least privilege:** ken `Mail.ReadWrite` alleen toe als je daadwerkelijk bijlagen boven die drempel verstuurt. Voor e-mails zonder bijlagen, of waarbij zowel elke losse bijlage als het totaal 2 MiB of kleiner is, is `Mail.Send` voldoende. `Mail.ReadWrite` als application permission geeft de app lees-, wijzig- en verwijderrechten op *alle* mailboxen in de tenant — laat deze machtiging weg waar mogelijk. Zonder `Mail.ReadWrite` mislukt het versturen van bijlagen groter dan 2 MiB met een 403-fout.

> **Beheerdersconsent vereist:** `Mail.Send` en `Mail.ReadWrite` zijn *applicatiemachtigingen* (niet delegated). Deze kunnen in Microsoft Entra ID niet door een gewone gebruiker worden toegekend — een tenant-/Entra-beheerder moet de machtigingen verlenen én er admin consent voor geven. Stem dit dus af met de beheerder van je organisatie voordat de plugin in gebruik wordt genomen. Ken alleen de strikt benodigde machtigingen toe (`Mail.Send`, en `Mail.ReadWrite` uitsluitend als je bijlagen groter dan 2 MiB verstuurt).

### Beperk de app registration tot functionele mailboxen (sterk aanbevolen)

De application permissions `Mail.Send` en `Mail.ReadWrite` gelden standaard **tenantbreed**: iedereen die het client secret bezit kan als élke gebruiker in de tenant mailen. Beperk de app registration daarom aan de Exchange Online-kant tot uitsluitend de functionele mailboxen die de plugin gebruikt, via een **Application Access Policy**:

```powershell
# 1. Maak een mail-enabled security group met de toegestane functionele mailboxen
New-DistributionGroup -Name "GraphMailPlugin-Senders" -Type Security
Add-DistributionGroupMember -Identity "GraphMailPlugin-Senders" -Member "noreply@gemeente.nl"

# 2. Beperk de app registration tot die groep
New-ApplicationAccessPolicy `
  -AppId "<client-id-van-de-app-registration>" `
  -PolicyScopeGroupId "GraphMailPlugin-Senders@gemeente.nl" `
  -AccessRight RestrictAccess `
  -Description "Graph Mail Plugin: alleen functionele mailboxen"

# 3. Verifieer
Test-ApplicationAccessPolicy -AppId "<client-id>" -Identity "willekeurige.gebruiker@gemeente.nl"
```

Microsoft faseert Application Access Policies op termijn uit ten gunste van **RBAC for Applications in Exchange Online** (resource-scoped `Mail.Send`-rollen via management scopes); gebruik dat mechanisme als het in jouw tenant beschikbaar is.

De `allowedSenders`-whitelist in de pluginconfiguratie (zie hieronder) is defense-in-depth *binnen* de plugin; de Application Access Policy is de daadwerkelijke tenant-grens en beschermt ook als het client secret buiten de plugin om wordt misbruikt. Configureer beide.

## Pluginconfiguratie

Maak een pluginconfiguratie aan in Valtimo via **Admin → Plugins → Graph Mail Plugin**.

| Eigenschap | Beschrijving | Verplicht |
|------------|-------------|-----------|
| `tenantId` | Azure Directory (tenant) ID | Ja |
| `clientId` | Azure Application (client) ID | Ja |
| `clientSecret` | Client secret van de App Registration | Ja |
| `allowedSenders` | Whitelist van toegestane afzenders: kommagescheiden volledige adressen (`noreply@gemeente.nl`) en/of domein-entries (`@gemeente.nl`) | Ja |
| `testSenderMailbox` | Standaard afzenderadres voor de test-send functie | Nee |

### Afzender-whitelist (`allowedSenders`)

De plugin hanteert **deny-by-default**: elke verzending wordt geweigerd tenzij het (eventueel via een procesvariabele aangeleverde) `senderMailbox`-adres voorkomt op de whitelist. Matching is hoofdletterongevoelig; een domein-entry (`@gemeente.nl`) staat het hele domein toe maar géén subdomeinen. De whitelist geldt ook voor het test-send endpoint.

> **Migratie:** pluginconfiguraties die vóór de introductie van `allowedSenders` zijn aangemaakt, weigeren na de upgrade elke verzending totdat de whitelist eenmalig is ingevuld en opgeslagen.

**Wijzigen vereist het client secret**

De whitelist bepaalt namens welke mailboxen de tenant-brede `Mail.Send`-machtiging via deze plugin gebruikt mag worden. Een adres toevoegen is daarmee feitelijk een rechtenuitbreiding: wie dat doet, kan vanaf dat moment als die mailbox mailen. Daarom is een gewijzigde whitelist alleen op te slaan wanneer het `clientSecret` in diezelfde request opnieuw wordt meegegeven — beheerschermtoegang alleen is niet genoeg, je moet de credential ook daadwerkelijk bezitten.

Blijft de whitelist ongewijzigd, dan verandert er niets: het secretveld mag leeg blijven en Valtimo behoudt de opgeslagen waarde. Herordenen of anders spatiëren van dezelfde adressen telt niet als wijziging; hoofdletters, dubbele vermeldingen en de bracket-notatie (`["a@x.nl","b@x.nl"]`, die de backend-parser ook accepteert) evenmin. Verwijderen telt wél als wijziging — versmallen is op zichzelf geen escalatie, maar zo kan "verwijderen en opnieuw toevoegen" geen omweg worden.

Dit wordt server-side afgedwongen (`AllowedSendersChangeGuard`), dus ook een directe `PUT /api/v1/plugin/configuration/{id}` die de frontend omzeilt krijgt een `400`. De controle grijpt in vóórdat Valtimo een leeg secretveld aanvult met de opgeslagen waarde — daarna is niet meer vast te stellen óf het secret is meegegeven.

> **Uitschakelen:** kan met `graph-mail.require-secret-for-allowlist-change: false`. Dat is een reële verzwakking en daarom een expliciete, zichtbare keuze. De plugin weigert op te starten wanneer de controle aan staat maar niet toegepast kan worden (bijvoorbeeld doordat een Valtimo-upgrade de onderliggende signatuur wijzigde) — een beveiligingscontrole die stilletjes wegvalt is erger dan een die nooit beloofd is. De opstartcontrole loopt daarvoor de advisor-keten van de daadwerkelijke proxy na en eist dat de pointcut élke `updatePluginConfiguration`-variant raakt; dat de bean een AOP-proxy ís zegt niets, want die is door `@Transactional` sowieso al geproxied. Daarnaast moet elke variant *inspecteerbaar* zijn — een configuratie-id én een `ObjectNode` met de ingediende properties dragen. De pointcut matcht namelijk op naam, dus een variant die de controle niet kan uitlezen wordt wél geadviseerd maar laat de wijziging ongecontroleerd door; alleen de herkende varianten verifiëren zou die blinde vlek juist openhouden.

**Hoe fijnmazig, naast een Exchange Online-scope?**

Met "Exchange Online-scope" wordt hier bedoeld: de [beperking van de app registration tot specifieke mailboxen](#beperk-de-app-registration-tot-functionele-mailboxen-sterk-aanbevolen), via een Application Access Policy óf via RBAC for Applications (zie hierboven — RBAC heeft de voorkeur waar beschikbaar). Die scope en de whitelist beantwoorden **verschillende vragen**, op verschillende niveaus:

| | Exchange Online-scope | `allowedSenders` |
|---|---|---|
| Vraag | Welke mailboxen mag deze *app registration* überhaupt aanraken? | Als welke mailbox mag *deze pluginconfiguratie* verzenden? |
| Bereik | Per app registration | Per pluginconfiguratie |
| Beheerd door | Exchange Online-beheerder | Beheerder van de pluginconfiguratie |

Die tweede vraag kan een Exchange Online-scope niet uitdrukken: twee pluginconfiguraties op dezelfde app registration zijn voor die scope identiek. Wil je één configuratie beperken tot `invordering@gemeente.nl` en een andere tot `noreply@gemeente.nl`, dan is de whitelist de enige plek waar dat kan.

Aanbevolen granulariteit:

| Situatie | `allowedSenders` |
|----------|------------------|
| Scope ingesteld, geverifieerd én aantoonbaar sluitend (zie de waarschuwing hieronder) | Domein-entry, bijv. `@gemeente.nl` |
| Geen scope, niet te verifiëren vanuit jouw rol, of twijfel of de scope sluitend is | Volledige adressen per mailbox |

Verifiëren doe je per mechanisme met de bijbehorende Exchange Online-cmdlet, en altijd tégen een concrete mailbox — anders test je of de rol bestaat, niet of de grens werkt:

| Mechanisme | Verificatie |
|---|---|
| Application Access Policy | `Test-ApplicationAccessPolicy -AppId "<client-id>" -Identity "<mailbox>"` |
| RBAC for Applications | `Test-ServicePrincipalAuthorization -Identity "<app>" -Resource "<mailbox>"` — zonder `-Resource` wordt de scope-controle niet uitgevoerd |

> **Let op bij RBAC — een scope alléén is niet sluitend.** Exchange-RBAC en Entra-applicatiemachtigingen zijn losse grants: RBAC-rollen werken volgens Microsoft *in aanvulling op* wat je in Entra ID toekent, niet als beperking daarop. Houdt dezelfde service principal daarnaast nog een tenantbrede `Mail.Send`-applicatiemachtiging in Entra ID, dan blijft die de verzending autoriseren buiten de RBAC-scope om.
>
> Dat is een stille valkuil: `Test-ServicePrincipalAuthorization` kan `InScope: False` melden voor een mailbox terwijl een Graph-`sendMail` naar diezelfde mailbox gewoon slaagt. De cmdlet toetst namelijk de RBAC-toewijzing, niet de Entra-machtiging die de aanroep alsnog toelaat.
>
> **Wil je op RBAC kunnen leunen, trek dan de tenantbrede `Mail.Send`-applicatiemachtiging in Entra ID in** en houd alleen de gescopete Exchange-rol over. Zolang die machtiging er nog staat, is `allowedSenders` de enige grens die daadwerkelijk actief is.

Bij de bovenste rij doet de Exchange Online-scope de afbakening per mailbox en blijft de whitelist een dunne domeincontrole — er zijn dan géén twee mailboxlijsten die synchroon gehouden moeten worden.

Ook met een correct ingestelde scope blijft de whitelist zinvol:

- De scope is niet zichtbaar vanuit Valtimo. De whitelist is de enige plek waar de bedoelde reikwijdte vastligt voor wie de plugin beheert.
- De scope hangt aan een security group respectievelijk een management scope die door een ander team beheerd wordt, en kan verschuiven zonder dat dat hier opvalt.
- Een afzender buiten de lijst wordt lokaal geweigerd vóór de tokenaanvraag, in plaats van als Graph-`403` midden in het proces — dat scheelt een incident en verbrande retries.

> Dit maakt de Exchange Online-scope niet overbodig: die blijft de primaire controle, omdat hij ook beschermt wanneer het client secret buiten de plugin om wordt misbruikt. De whitelist kan dat niet.


## Actie: send-email

Verstuur een e-mail vanuit een BPMN-serviceTask.

| Parameter | Beschrijving | Verplicht |
|-----------|-------------|-----------|
| `senderMailbox` | E-mailadres van de afzender — moet voorkomen op de `allowedSenders`-whitelist van de pluginconfiguratie | Ja |
| `recipients` | Ontvangers — enkelvoudig adres, kommalijst of JSON-array | Ja |
| `cc` | CC-ontvangers | Nee |
| `bcc` | BCC-ontvangers | Nee |
| `replyTo` | Reply-To adressen | Nee |
| `subject` | Onderwerp van de e-mail | Ja |
| `contentId` | Resource-ID van de HTML-body in tijdelijke opslag | Ja |
| `attachmentIds` | Resource-ID('s) van bijlagen in tijdelijke opslag | Nee |

## Aandachtspunten

**Weergavenaam afzender**
De weergavenaam die de ontvanger ziet, is de Display Name die is ingesteld op de afzendermailbox in Microsoft 365. De plugin heeft geen mogelijkheid om de weergavenaam te overschrijven. Pas de gewenste weergavenaam aan via het Microsoft 365 Admin Center.

**Opslaan in Verzonden items**
E-mails verzonden via de `send-email` actie worden opgeslagen in de Sent Items van de afzendermailbox. E-mails verstuurd via de test-send functie op de configuratiepagina worden *niet* opgeslagen.

**Bijlagen — twee verzendpaden**
Bijlagen van 2 MiB of kleiner worden inline (base64) meegestuurd in de sendMail-aanroep (alleen `Mail.Send` nodig). Als een bijlage — of het totaal aan bijlagen — groter is dan 2 MiB, verstuurt de plugin automatisch via een Graph API upload-sessie (concept → chunked upload → verzenden); dit pad vereist `Mail.ReadWrite`. Bij de upload-sessie is het verzendtijdstip het moment van de definitieve verzendaanroep, niet het moment van conceptaanmaak.

**Dubbele verzending bij transactieretry**
De plugin-actie vuurt op `SERVICE_TASK_START`. Als de Operaton-transactie na een geslaagde verzending alsnog terugdraait (bijvoorbeeld door een optimistic lock op andere procesdata) en de activiteit opnieuw uitvoert, is de e-mail bij Graph al onomkeerbaar geaccepteerd. De plugin herkent deze herhaling zelf en slaat de tweede Graph-aanroep over; je hoeft hier in het procesmodel niets voor in te richten.

De herkenning gebeurt op een **teller per activity**, die de plugin als procesvariabele bijhoudt (`graphMailPass_<activityId>`). Die teller wordt gelezen vóórdat hij wordt opgehoogd en via de execution weggeschreven, dus hij deelt het lot van de transactie: rolt die terug, dan rolt de ophoging mee en leest de retry hetzelfde nummer; committeert een iteratie, dan begint de volgende bij een hoger nummer.

> **Let op:** de plugin schrijft daarmee een variabele op elke procesinstantie die mail verstuurt. Die is zichtbaar in Cockpit en in de variabelenhistorie. De naam is genamespaced en per activity, zodat twee send-email-taken in één proces onafhankelijk tellen.

Dat lijkt omslachtig, maar geen van de voor de hand liggende alternatieven werkt — beide zijn tegen een echte engine getest in `ActivityInstanceIdContractTest`:

| Sleutel | Stabiel over retry? | Uniek per loop-iteratie? |
|---------|--------------------|--------------------------|
| `execution.id` + `currentActivityId` | ja | **nee** — elke volgende mail in een loop werd weggegooid |
| `activityInstanceId` | **nee** — krijgt per poging een nieuw nummer | ja |
| teller in procesvariabele | ja | ja |

De eerste drie velden kunnen de twee situaties principieel niet scheiden: bij zowel een retry als een loop-iteratie is `execution.id` gelijk, `currentActivityId` gelijk en `activityInstanceId` verschillend. Een hash over de mailinhoud is evenmin bruikbaar: een aanmaningsproces dat bewust twee keer dezelfde mail stuurt zou dan stilzwijgend worden ingeslikt.

> **Let op — een procesvariabele werkt hier níet als guard.** Die wordt geschreven binnen dezelfde transactie die terugrolt, dus hij verdwijnt samen met de retry en is voor de volgende poging nooit zichtbaar. Daarom gebruikt de plugin een bewust niet-transactionele, in-geheugen guard.

> **Beperking:** de guard beschermt tegen een retry die dezelfde, nog draaiende JVM-instantie afhandelt — het realistische scenario, waarbij de retry milliseconden tot seconden later plaatsvindt. Hij overleeft géén herstart van de applicatie tussen de oorspronkelijke verzending en een latere retry. Is die garantie in jouw situatie nodig, dan is aanvullende deduplicatie aan de ontvangerskant het aangewezen middel.

**Wanneer de guard de verzending niet meer herkent**

De markering leeft in het geheugen van één JVM en verloopt na 30 minuten. Daaruit volgen drie situaties waarin een retry een al verzonden mail niet als duplicaat ziet en hem opnieuw verstuurt — zonder foutmelding, want technisch gaat er niets mis:

| Situatie | Gevolg | Wat je eraan doet |
|---|---|---|
| **Multi-node deployment** | De job-executor is cluster-breed. Wordt de retry door een andere node opgepakt dan de node die verstuurde, dan kent die de markering niet. | Deduplicatie aan ontvangerskant, of accepteer dat de bescherming best-effort is. Er is geen instelling die dit binnen de plugin oplost. |
| **Retry later dan 30 minuten** | De markering is dan al verlopen. Zie de waarschuwing bij `failedJobRetryTimeCycle` hieronder. | Houd de totale duur van je retry-cyclus onder 30 minuten. |
| **Herstart tussen verzending en retry** | De markering is met de JVM verdwenen. | Deduplicatie aan ontvangerskant. |

Bij één node, een retry-cyclus binnen het plafond en geen herstart — het gangbare geval — werkt de bescherming zoals beschreven. De TTL van 30 minuten is een constante in `SendIdempotencyGuard` en niet via configuratie aan te passen.

**Transportfouten worden bewust niet opnieuw geprobeerd**
Een netwerkfout of read-timeout op de verzendaanroep zelf (`sendMail`, `messages/{id}/send`) zegt niets over of Graph het bericht al heeft geaccepteerd. De plugin probeert die aanroep daarom **niet** automatisch opnieuw en meldt de fout als `GraphMailUnknownOutcomeException` — beter één onzekere verzending dan een gegarandeerde dubbele mail bij de ontvanger. Conceptaanmaak en het aanmaken van een upload-sessie zijn wél herhaalbaar en worden wel opnieuw geprobeerd.

Controleer bij deze fout de mailbox voordat de activity opnieuw wordt uitgevoerd. In de auditlog is dit herkenbaar aan `verdict=UNKNOWN`.

**Foutclassificatie in de auditlog**
Elke mislukte verzending logt een `verdict`-veld dat aangeeft wat de beheerder moet doen:

| Verdict | Betekenis |
|---------|-----------|
| `PERMANENT_INPUT` | Invoer- of configuratiefout; opnieuw proberen faalt identiek. Corrigeer de procesdata of de pluginconfiguratie. |
| `PERMANENT_REMOTE` | Graph weigert dit permanent (bijv. 403 zonder `Mail.Send`, 404 onbekende mailbox). Vereist een configuratie- of permissiewijziging. |
| `UNKNOWN` | Transportfout na verzending; de mail is mogelijk wél verstuurd. Verifieer voordat je opnieuw uitvoert. |
| `TRANSIENT` | Tijdelijk (429/5xx, of een netwerkfout op een herhaalbare stap zoals conceptaanmaak, het aanmaken van een upload-sessie, of een verbinding die nooit tot stand kwam); de job-executor probeert het opnieuw. Een transportfout op `sendMail` of `messages/{id}/send` nádat het verzoek verstuurd is valt hier **niet** onder — die is `UNKNOWN`. |

Deze classificatie zit bewust in de logging en niet in een `BpmnError`: het omzetten van permanente fouten naar een BPMN-fout zou de procesafhandeling van elk bestaand model wijzigen, en een niet-afgevangen `BpmnError` degradeert tot een incident met de melding "no catching boundary event found" — minder bruikbaar dan de fout die de plugin nu gooit. Wil je permanente fouten in het procesmodel afvangen, gebruik dan een `failedJobRetryTimeCycle` in combinatie met een incident-handler.
**HTML-body sanitisatie**
De HTML-body wordt automatisch gesanitiseerd via jsoup vóór verzending. Toegestaan: opmaaktags, tabellen, inline `style`-attributen, `<img>` met http/https/cid-bronnen. Verwijderd: `<style>`-blokken, `<script>`, iframes, `data:` URI's, JavaScript-eventattributen. Ook binnen toegestane inline `style`-attributen worden `url(...)`, `@import`, `expression(...)` en `javascript:` weggefilterd — anders zou een `style="background:url(https://tracker/pixel.png)"` alsnog een externe request (tracking pixel) veroorzaken, precies waarvoor `<style>`-blokken geweerd worden. De overige stijlregels blijven intact. Als de body na sanitisatie leeg is, gooit de plugin een fout — controleer de HTML-inhoud die is opgeslagen op het opgegeven `contentId`.

**Limieten**

| Limiet | Waarde |
|--------|--------|
| Max ontvangers per veld (To / Cc / Bcc) | 100 |
| Max ontvangers totaal (To + Cc + Bcc) | 200 |
| Max onderwerpregel | 255 tekens |
| Max body-grootte | 5 MiB |
| Max bijlagen | 5 |
| Max grootte per bijlage | 25 MiB |
| Max totale bijlagegrootte | 25 MiB |

**Secret management**
Het `clientSecret` is een Valtimo secret property (`@PluginProperty(secret = true)`): het wordt AES-versleuteld opgeslagen in de database en nooit teruggestuurd naar de frontend. De encryptiesleutel komt uit de applicatieproperty `valtimo.plugin.encryption-secret` en moet exact 16, 24 of 32 bytes lang zijn. Zet deze sleutel **nooit** in de repository of in een gecommit configuratiebestand — lever hem aan via een environment variable of een secret store (Azure Key Vault, HashiCorp Vault, Kubernetes Secrets):

```yaml
valtimo:
  plugin:
    encryption-secret: ${VALTIMO_PLUGIN_ENCRYPTION_SECRET}
```

Wie deze sleutel én een databasedump bezit, kan alle plugin-secrets (waaronder het Graph client secret) ontsleutelen. Roteer het client secret in Azure periodiek en behandel de encryptiesleutel met hetzelfde beveiligingsniveau als de secrets zelf.

**Endpoints zijn deployment-instellingen, geen pluginproperties**
`tokenBaseUrl` en `graphBaseUrl` waren eerder per pluginconfiguratie instelbaar vanuit de beheer-UI. Dat was een exfiltratiepad voor het client secret: dat secret wordt als formulierveld naar `tokenBaseUrl` gePOST, dus wie pluginconfiguraties mocht beheren kon het naar een eigen host laten sturen. `graphBaseUrl` gaf daarnaast een SSRF-primitief, en de hostcontrole op de upload-URL leidde haar verwachte host áf uit `graphBaseUrl` — waardoor die controle precies zo sterk was als de waarde die een beheerder had ingevuld.

Deze instellingen staan nu onder `graph-mail.http` en worden bij het opstarten gevalideerd tegen een vaste allowlist van Microsoft-endpoints (inclusief de sovereign clouds). Een afwijkende waarde laat de applicatie falen bij opstarten met een leesbare melding.

```yaml
graph-mail:
  http:
    token-base-url: https://login.microsoftonline.com   # default
    graph-base-url: https://graph.microsoft.com         # default
    connect-timeout-seconds: 10
    read-timeout-seconds: 30
    attachment-concurrency: 8                # max gelijktijdige verzendingen mét bijlagen
    attachment-acquire-timeout-seconds: 30
    # allow-non-microsoft-endpoints: true    # UITSLUITEND voor tests / lokale sandbox
```

> **Migratie:** bestaande pluginconfiguraties met een `tokenBaseUrl`- of `graphBaseUrl`-waarde negeren die waarde na deze upgrade. Stond er een niet-standaard endpoint in, dan kun je dat **niet** ongewijzigd overzetten: `graph-mail.http` accepteert alleen endpoints uit de allowlist hierboven, en een afwijkende waarde laat de applicatie falen bij opstarten. Vervang zo'n endpoint door het juiste Microsoft-endpoint voor jouw cloud (`allow-non-microsoft-endpoints` is uitsluitend bedoeld voor tests en een lokale sandbox). `connectTimeoutSeconds` en `readTimeoutSeconds` kunnen wél ongewijzigd mee als `connect-timeout-seconds` en `read-timeout-seconds`.

**Geheugengebruik bij bijlagen**
Een verzending met bijlagen houdt de volledige inhoud in het heap-geheugen. Zonder rem zou het aantal gelijktijdige verzendingen gelijk zijn aan de grootte van de job-executor thread-pool, wat bij de hieronder aanbevolen `max-pool-size: 50` neerkomt op meerdere gigabytes en dus een `OutOfMemoryError`. `graph-mail.http.attachment-concurrency` begrenst dit los van de thread-pool; verzendingen zónder bijlagen worden niet begrensd. Wordt binnen `attachment-acquire-timeout-seconds` geen slot vrij, dan faalt de verzending als *transient* en probeert de job-executor het later opnieuw.

**Connection pooling**
De plugin gebruikt één gedeelde, gepoolde HTTP-client (de JDK `HttpClient`) voor alle verzendingen. Eerder werd per plugin-actie-invocatie een nieuwe `RestTemplate` opgebouwd — Valtimo hydrateert namelijk een nieuwe plugin-instantie per actie — waardoor er per e-mail een volledige TLS-handshake nodig was en verbindingen nooit hergebruikt werden. Er is bewust geen afhankelijkheid van Apache HttpClient5 toegevoegd, zodat de plugin niets hoeft aan te nemen over de HTTP-bibliotheken op het classpath van de omringende GZAC-applicatie. De client staat expliciet op HTTP/1.1: de JDK-client kiest standaard HTTP/2, en dat zou het wire-protocol wijzigen als bijeffect van een performance-refactor. Verbindingshergebruik komt van keep-alive en werkt op HTTP/1.1 identiek.

**Rate limiting test-send**
Het test-send endpoint staat maximaal 1 verzoek per gebruiker per 10 seconden toe. De teller wordt in geheugen bijgehouden per JVM-instantie. Bij een multi-node deployment geldt de limiet per node afzonderlijk.

**Job executor thread-blokkering — verplichte configuratie**

De retry-backoff gebruikt `Thread.sleep()`, maar **alleen binnen een wachtbudget van 2 seconden per aanroep**. Zodra een volgende wachtperiode dat budget zou overschrijden, geeft de plugin de thread op en meldt de fout als *transient*, zodat de job-executor het later opnieuw inplant zónder een thread vast te houden.

Dat verschil is wezenlijk. Graph throttlet mail per mailbox stevig, en eerder honoreerde de plugin een `Retry-After` van 15 seconden vijf keer achter elkaar. Alle job-executor threads lagen dan tegelijk te slapen en de engine verwerkte géén enkele job meer — ook niets dat met e-mail te maken had. Eén externe API die de hele engine platlegt is een ernstiger storing dan een verzending die later slaagt.

Korte haperingen worden nog steeds in de aanroep zelf opgevangen; één backoff van 500 ms uitzitten is goedkoper dan een job herplannen.

> **Configureer daarom een `failedJobRetryTimeCycle`** op de send-email service task, bijvoorbeeld `R5/PT2M`. Zonder dat valt de plugin terug op de standaard retry-instelling van de engine, die voor throttling meestal te kort is.

> **Let op — er zit een plafond aan die cyclus.** De duplicaatbescherming vergeet een verzending na 30 minuten. Duurt je retry-cyclus langer dan dat, dan is de markering verlopen op het moment dat de laatste pogingen binnenkomen, en wordt een al verzonden mail opnieuw verstuurd. Reken de totale duur dus uit, niet alleen het interval:
>
> | Cyclus | Totale duur | Veilig |
> |---|---|---|
> | `R5/PT2M` | 10 minuten | ja |
> | `R3/PT5M` | 15 minuten | ja |
> | `R5/PT10M` | 50 minuten | **nee** — laatste retries vallen buiten de bescherming |
> | `R3/PT1H` | 3 uur | **nee** |
>
> Heb je een lange cyclus nodig omdat de throttling van jouw tenant daarom vraagt, kies dan bewust: langere retries óf duplicaatbescherming. Beide tegelijk kan de plugin niet garanderen.

Resterende maximale blokkeerttijden per verzending:

| Situatie | Maximale blokkeerttijd |
|----------|----------------------|
| Wachten op een nieuwe poging (backoff, `Retry-After`) | 2 seconden totaal per aanroep |
| Reguliere verzending, inclusief netwerktijd | 30 seconden |
| Verzending via upload-sessie (bijlage > 2 MiB) | 120 seconden |

De twee onderste grenzen zijn geen wachttijd maar werk: het overzetten van een bijlage van 25 MB duurt nu eenmaal. Alleen de bovenste was de bron van de engine-brede stilstand, en die is nu begrensd.

**Minimum vereiste configuratie — voeg dit toe aan `application.yml`:**

```yaml
operaton:
  bpm:
    job-executor:
      core-pool-size: 20
      max-pool-size: 50
```

Bij minder dan 20 threads loop je een reëel risico op een vastgelopen job-executor onder normale productielast. De plugin logt een waarschuwing bij opstarten als herinnering.

> **Let op (queue-size):** bij een thread-pool-executor worden threads bóven `core-pool-size` pas aangemaakt wanneer de wachtrij vol is. Staat `queue-size` hoog, dan blijft de pool in de praktijk op `core-pool-size` steken en doet `max-pool-size` niets. Houd `queue-size` daarom klein als je op de extra threads wilt kunnen leunen, en stem het totale aantal threads af op de database-connectiepool (meer werkers betekent meer gelijktijdige verbindingen).

**Geheugengebruik — schaalt mee met het aantal threads**

Bijlagen en de body worden volledig in het geheugen gehouden zolang een verzending loopt; er wordt niet naar schijf gestreamd. De piek per gelijktijdige verzending is daarmee ruwweg:

| Onderdeel | Maximum |
|-----------|---------|
| Bijlagen (totaal) | 25 MiB |
| HTML-body | 5 MiB |
| Chunk-buffer bij de upload-sessie | 3,2 MiB |
| **Piek per verzending** | **≈ 33 MiB** |

Dit vermenigvuldigt met het aantal threads dat tegelijk kan verzenden. Met de aanbevolen `max-pool-size: 50` betekent dat in het uiterste geval ruim **1,6 GB heap** die alleen aan e-mails in transit opgaat. Houd hier rekening mee bij het instellen van `-Xmx`, en verhoog `max-pool-size` niet zonder de heap navenant mee te schalen — anders ruil je een vastgelopen job-executor in voor `OutOfMemoryError`.

Verstuur je zelden of nooit grote bijlagen, dan is de praktijkpiek een fractie hiervan: zonder bijlagen blijft het bij de body plus wat overhead.

## Test-send

Via de pluginconfiguratiepagina in Valtimo kan een testmail worden verstuurd om te verifiëren dat de Azure-credentials correct zijn geconfigureerd. Dit vereist de rol `ROLE_ADMIN`. De afzender van de testmail moet — net als bij de `send-email`-actie — voorkomen op de `allowedSenders`-whitelist van de pluginconfiguratie.
