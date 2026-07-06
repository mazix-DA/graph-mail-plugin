# Graph Mail Plugin Documentatie

Verstuur e-mails via de Microsoft Graph API met OAuth2 (Client Credentials flow).

## Vereisten

Een **Azure App Registration** met de volgende instellingen:

- Applicatiemachtiging: `Mail.Send` (niet delegated) — vereist voor alle e-mailverzendingen
- Applicatiemachtiging: `Mail.ReadWrite` (niet delegated) — **alleen** vereist voor bijlagen groter dan 2 MiB; de plugin maakt dan eerst een conceptbericht aan via de Graph API upload-sessie flow
- Een client secret aangemaakt onder *Certificates & secrets*

> **Least privilege:** ken `Mail.ReadWrite` alleen toe als je daadwerkelijk bijlagen groter dan 2 MiB verstuurt. Voor e-mails zonder bijlagen of met bijlagen tot 2 MiB is `Mail.Send` voldoende. `Mail.ReadWrite` als application permission geeft de app lees-, wijzig- en verwijderrechten op *alle* mailboxen in de tenant — laat deze machtiging weg waar mogelijk. Zonder `Mail.ReadWrite` mislukt het versturen van bijlagen groter dan 2 MiB met een 403-fout.

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
De plugin-actie vuurt op `SERVICE_TASK_START`. Als de Operaton-transactie terugdraait en opnieuw start (bijvoorbeeld bij een optimistic lock conflict), kan de e-mail meer dan één keer worden verzonden. Mitigatie: sla een idempotency-token op als procesvariabele en dedupliceer aan de ontvangerskant.

**HTML-body sanitisatie**
De HTML-body wordt automatisch gesanitiseerd via jsoup vóór verzending. Toegestaan: opmaaktags, tabellen, inline `style`-attributen, `<img>` met http/https/cid-bronnen. Verwijderd: `<style>`-blokken, `<script>`, iframes, `data:` URI's, JavaScript-eventattributen. Als de body na sanitisatie leeg is, gooit de plugin een fout — controleer de HTML-inhoud die is opgeslagen op het opgegeven `contentId`.

**Limieten**

| Limiet | Waarde |
|--------|--------|
| Max ontvangers per veld (To / Cc / Bcc) | 100 |
| Max ontvangers totaal (To + Cc + Bcc) | 200 |
| Max onderwerpregel | 255 tekens |
| Max body-grootte | 5 MB |
| Max bijlagen | 5 |
| Max grootte per bijlage | 25 MB |
| Max totale bijlagegrootte | 25 MB |

**Secret management**
Het `clientSecret` is een Valtimo secret property (`@PluginProperty(secret = true)`): het wordt AES-versleuteld opgeslagen in de database en nooit teruggestuurd naar de frontend. De encryptiesleutel komt uit de applicatieproperty `valtimo.plugin.encryption-secret` en moet exact 16, 24 of 32 bytes lang zijn. Zet deze sleutel **nooit** in de repository of in een gecommit configuratiebestand — lever hem aan via een environment variable of een secret store (Azure Key Vault, HashiCorp Vault, Kubernetes Secrets):

```yaml
valtimo:
  plugin:
    encryption-secret: ${VALTIMO_PLUGIN_ENCRYPTION_SECRET}
```

Wie deze sleutel én een databasedump bezit, kan alle plugin-secrets (waaronder het Graph client secret) ontsleutelen. Roteer het client secret in Azure periodiek en behandel de encryptiesleutel met hetzelfde beveiligingsniveau als de secrets zelf.

De properties `tokenBaseUrl` en `graphBaseUrl` zijn bedoeld voor tests en zouden in productie altijd op de standaard Microsoft-endpoints moeten staan; een gewijzigde `tokenBaseUrl` betekent dat het client secret naar een ander endpoint wordt gestuurd. Beperk toegang tot pluginconfiguratiebeheer daarom tot vertrouwde beheerders.

**Rate limiting test-send**
Het test-send endpoint staat maximaal 1 verzoek per gebruiker per 10 seconden toe. De teller wordt in geheugen bijgehouden per JVM-instantie. Bij een multi-node deployment geldt de limiet per node afzonderlijk.

**Job executor thread-blokkering — verplichte configuratie**

De retry-backoff gebruikt `Thread.sleep()`, waardoor de aanroepende Operaton job-executor thread geblokkeerd wordt tijdens het wachten op een nieuwe poging. Maximale blokkeerttijden per verzending:

| Situatie | Maximale blokkeerttijd |
|----------|----------------------|
| Reguliere verzending (geen grote bijlagen) | 30 seconden |
| Verzending via upload-sessie (bijlage > 2 MiB) | 120 seconden |
| 429 rate-limit sleep per poging (max) | 15 seconden |

Als meerdere processen tegelijk e-mails versturen terwijl de Graph API rate-limiteert, kunnen alle job-executor threads tegelijkertijd geblokkeerd worden. Dit stopt de verwerking van alle andere Operaton-taken in de applicatie.

**Minimum vereiste configuratie — voeg dit toe aan `application.yml`:**

```yaml
operaton:
  bpm:
    job-executor:
      core-pool-size: 20
      max-pool-size: 50
```

Bij minder dan 20 threads loop je een reëel risico op een vastgelopen job-executor onder normale productielast. De plugin logt een waarschuwing bij opstarten als herinnering.

## Test-send

Via de pluginconfiguratiepagina in Valtimo kan een testmail worden verstuurd om te verifiëren dat de Azure-credentials correct zijn geconfigureerd. Dit vereist de rol `ROLE_ADMIN`. De afzender van de testmail moet — net als bij de `send-email`-actie — voorkomen op de `allowedSenders`-whitelist van de pluginconfiguratie.
