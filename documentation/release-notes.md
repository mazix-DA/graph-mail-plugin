# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- Bug gefixt in de test-mail footer: door een verkeerd gebruikte escape-sequence (`${'$'}escapedSender`
  in plaats van gewone interpolatie) toonde elke testmail letterlijk de tekst `$escapedSender` in
  plaats van het daadwerkelijk gebruikte afzenderadres.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
