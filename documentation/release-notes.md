# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.4

### Actie vereist

**Endpoints en timeouts verhuizen naar `application.yml`.** `tokenBaseUrl`, `graphBaseUrl`,
`connectTimeoutSeconds` en `readTimeoutSeconds` zijn geen pluginproperty meer. Ze staan nu onder
`graph-mail.http`. Bestaande pluginconfiguraties met afwijkende waarden negeren die na de upgrade;
zet ze over. Alleen Microsoft-endpoints worden geaccepteerd — een eigen host laat de applicatie bij
opstarten falen. Zie [plugin.md](plugin.md).

**De afzender-whitelist wijzigen vraagt nu om het client secret.** Wie `allowedSenders` aanpast,
moet het `clientSecret` opnieuw invullen. Ongewijzigd laten verandert niets. Herordenen, andere
spatiëring of hoofdletters tellen niet als wijziging; een adres verwijderen wel. Uit te schakelen
met `graph-mail.require-secret-for-allowlist-change: false`.

**Stel een `failedJobRetryTimeCycle` in** op de send-email service task, bijvoorbeeld `R5/PT2M`.
De plugin geeft een verzending nu na hooguit twee seconden wachten terug aan de engine in plaats van
de thread bezet te houden, en leunt daarvoor op de retry-instelling van de taak.

**De plugin schrijft een procesvariabele.** Elke procesinstantie die mail verstuurt krijgt
`graphMailPass_<activityId>`. Die is zichtbaar in Cockpit en in de variabelenhistorie.

### Opgelost

- Achter een uitgaande proxy faalde elke verzending met een connectiefout, en de job-executor bleef
  het eindeloos opnieuw proberen. De plugin gebruikt nu weer de proxy-instellingen van de JVM en
  logt bij opstarten welke proxy in gebruik is. Een afwijkende proxy stel je in met
  `graph-mail.http.proxy-host` en `proxy-port`.
- Een loop over dezelfde service task verstuurde alleen de eerste e-mail. De rest werd stil
  overgeslagen terwijl het proces doorliep alsof er verstuurd was.
- Bij een teruggedraaide transactie kon dezelfde mail alsnog twee keer uitgaan.
- Een read-timeout op de verzendaanroep leidde tot herhaalde pogingen, waardoor de ontvanger
  meerdere kopieën kon krijgen. Zo'n verzending wordt nu gemeld als `verdict=UNKNOWN`: mogelijk
  aangekomen, controleer de mailbox.
- Bij throttling door Graph lag de hele engine stil, ook voor werk dat niets met e-mail te maken had.
- Een verlopen token kon de melding opleveren dat `Mail.Send` ontbreekt.
- Een verzending die op het antwoord time-oute, verwijderde het bericht daarna uit Verzonden items.
- Een onderbroken upload van een grote bijlage gooide de hele upload weg, of leverde een bijlage met
  een gat erin.
- Verzenden vanuit een sovereign cloud (US Gov, China) faalde altijd bij het ophalen van het token.
- Throttling van Entra werd gemeld als "controleer Client ID en Secret".
- De harde tijdslimiet van 30 seconden per verzending kon met tientallen seconden overschreden worden.
- Elke mislukte verzending logt nu waarom hij mislukte en of opnieuw proberen zin heeft.

### Beveiliging

- Een tracking-pixel kon via `style="background:url(...)"` alsnog door de HTML-filter komen. Ook
  CSS-escapes en -commentaar worden nu herkend.
- Een verkeerd getypt client secret in het testmail-scherm logde de beheerder uit.
- E-mailadressen worden nu ook gemaskeerd in foutmeldingen van het testmail-endpoint.

### Overig

- Eén gedeelde HTTP-verbinding voor alle verzendingen in plaats van een nieuwe per e-mail.
- `graph-mail.http.attachment-concurrency` begrenst hoeveel verzendingen met bijlagen tegelijk
  lopen. Zonder die grens kon een piek in bijlagen het geheugen laten vollopen.

## 1.0.3

### Actie vereist

**`allowedSenders` is verplicht geworden.** Elke verzending wordt geweigerd tenzij het
afzenderadres op de whitelist van de pluginconfiguratie staat. Bestaande configuraties versturen na
de upgrade **niets meer** totdat de lijst eenmalig is ingevuld en opgeslagen. Volledige adressen en
`@domein`-entries mogen allebei. Geldt ook voor het testmail-endpoint.

### Opgelost

- Bij een teruggedraaide transactie kon dezelfde mail twee keer uitgaan. (Werkte nog niet volledig;
  zie 1.0.4.)
- De testmail toonde letterlijk `$escapedSender` in de voettekst in plaats van het afzenderadres.
- Het aanmaken van een upload-sessie voor grote bijlagen probeerde het nooit opnieuw bij throttling,
  en de conceptflow niet bij netwerkfouten.
- De rate-limiter van het testmail-endpoint hield voor elke gebruiker permanent een entry vast.

### Beveiliging

- Tokens werden niet gecached over verzendingen heen, waardoor elke e-mail een nieuwe tokenaanvraag
  deed. Een configuratie met een verkeerd secret kon bovendien meeliften op het token van een
  andere configuratie voor dezelfde tenant.
- Het client secret en het access-token konden in cleartext in de log belanden.
- Het afzenderadres stond ongemaskeerd in het event van het testmail-endpoint.
- `jsoup` naar 1.23.1 (CVE-2026-71497). De filterinstellingen van deze plugin waren niet
  daadwerkelijk kwetsbaar, maar het is de bibliotheek waar de HTML-sanitisatie op leunt.
- Script-injectie in de publicatie-workflows gedicht en `tj-actions/changed-files` op een commit-SHA
  vastgezet in plaats van een verplaatsbare tag (CVE-2025-30066).

### Overig

- `cn.lalaki.central` stond als runtime-dependency in de gepubliceerde POM, waardoor elke consument
  een Gradle-publicatieplugin binnenhaalde. Verwijderd.
- De frontend bouwt API-URL's nu via `ConfigService`, zodat de plugin ook werkt wanneer frontend en
  backend op verschillende origins draaien.
- ktlint toegevoegd aan de PR-checks; Dependabot ingesteld voor gradle, npm en github-actions.

## 1.0.2

Valtimo bijgewerkt naar versie 13.41.0.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
