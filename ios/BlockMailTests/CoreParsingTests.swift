import XCTest
@testable import BlockMail

/// Tests der selbst geschriebenen IMAP-/MIME-Schicht (ersetzt JavaMail).
final class CoreParsingTests: XCTestCase {

    private func fetch(_ raw: String) -> IMAPFetch {
        // "* 12 FETCH (...)" ohne "* " wie in IMAPClient.readResponse
        let body = raw.hasPrefix("* ") ? String(raw.dropFirst(2)) : raw
        let t = IMAPTokenizer.parse(Data(body.utf8))
        XCTAssertEqual(t[1].text?.uppercased(), "FETCH")
        let list = t[2].list ?? []
        var dict: [String: IMAPValue] = [:]
        var i = 0
        while i + 1 < list.count {
            var key = list[i].text!.uppercased()
            if let lt = key.firstIndex(of: "<"), key.hasSuffix(">") { key = String(key[..<lt]) }
            dict[key] = list[i + 1]
            i += 2
        }
        return IMAPFetch(seq: Int(t[0].int64 ?? 0), items: dict)
    }

    func testEnvelopeFlagsAndStructure() {
        let raw = #"* 3 FETCH (UID 4711 FLAGS (\Seen \Flagged) INTERNALDATE "29-Sep-2026 10:15:00 +0200" ENVELOPE ("Tue, 29 Sep 2026 10:14:59 +0200" "=?UTF-8?Q?Gr=C3=BC=C3=9Fe_aus_M=C3=BCnchen?=" (("=?ISO-8859-1?Q?J=FCrgen?=" NIL "juergen" "example.de")) NIL NIL (("Ich" NIL "me" "gmail.com")) NIL NIL NIL "<abc@example.de>") BODYSTRUCTURE ((("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "QUOTED-PRINTABLE" 120 4 NIL NIL NIL)("TEXT" "HTML" ("CHARSET" "UTF-8") NIL NIL "BASE64" 800 11 NIL NIL NIL) "ALTERNATIVE" ("BOUNDARY" "b1") NIL NIL)("IMAGE" "PNG" ("NAME" "logo.png") "<logo1>" NIL "BASE64" 4000 NIL ("INLINE" ("FILENAME" "logo.png")) NIL)("APPLICATION" "PDF" ("NAME" "Rechnung.pdf") NIL NIL "BASE64" 90000 NIL ("ATTACHMENT" ("FILENAME*" "UTF-8''Rechnung%20M%C3%A4rz.pdf")) NIL) "MIXED" ("BOUNDARY" "b0") NIL NIL))"#
        let f = fetch(raw)
        XCTAssertEqual(f.uid, 4711)
        XCTAssertTrue(f.flags.contains("\\seen"))
        XCTAssertTrue(f.flags.contains("\\flagged"))
        XCTAssertNotNil(f.internalDate)
        let env = f.envelope!
        XCTAssertEqual(env.subject, "Grüße aus München")
        XCTAssertEqual(env.from.first?.name, "Jürgen")
        XCTAssertEqual(env.from.first?.email, "juergen@example.de")
        XCTAssertEqual(env.to.first?.email, "me@gmail.com")
        let bs = f.bodyStructure!
        XCTAssertTrue(bs.isMultipart)
        XCTAssertEqual(bs.firstPart(mime: "text/plain")?.section, "1.1")
        XCTAssertEqual(bs.firstPart(mime: "text/html")?.section, "1.2")
        let leaves = bs.leaves
        XCTAssertEqual(leaves.count, 4)
        XCTAssertEqual(leaves[2].contentID, "logo1")
        XCTAssertFalse(leaves[2].isAttachment) // eingebettetes Bild
        XCTAssertTrue(leaves[3].isAttachment)
        XCTAssertEqual(leaves[3].fileName, "Rechnung März.pdf")
        XCTAssertEqual(leaves[3].section, "3")
        XCTAssertTrue(bs.hasAttachments)
        let m = MailRepository.toMailMessage(f)!
        XCTAssertEqual(m.from, "Jürgen")
        XCTAssertTrue(m.seen)
        XCTAssertTrue(m.flagged)
        XCTAssertTrue(m.hasAttachments)
    }

    func testSinglePartAndLiteral() {
        let raw = "* 1 FETCH (UID 9 BODY[1] {11}\r\nHallo Welt!)\r\n"
        let f = fetch(raw)
        XCTAssertEqual(f.uid, 9)
        XCTAssertEqual(f.section("1").map { String(decoding: $0, as: UTF8.self) }, "Hallo Welt!")
        let single = IMAPTokenizer.parse(Data(#"("TEXT" "PLAIN" ("CHARSET" "iso-8859-1") NIL NIL "7BIT" 20 1 NIL NIL NIL)"#.utf8))
        let bp = BodyPart.parse(single[0], section: "")!
        XCTAssertEqual(bp.section, "1")
        XCTAssertEqual(bp.charset, "iso-8859-1")
    }

    func testHeaderFieldsKey() {
        let raw = "* 2 FETCH (UID 5 BODY[HEADER.FIELDS (LIST-UNSUBSCRIBE)] {48}\r\nList-Unsubscribe: <https://x.example/u?id=1>\r\n\r\n)"
        let f = fetch(raw)
        let hdr = String(decoding: f.section("HEADER.FIELDS (LIST-UNSUBSCRIBE)")!, as: UTF8.self)
        XCTAssertEqual(MIMEDecode.headerValue(hdr, "List-Unsubscribe"), "<https://x.example/u?id=1>")
    }

    func testTransferEncodings() {
        let qp = MIMEDecode.transfer(Data("Gr=C3=BC=C3=9Fe=\r\n Welt =3D ok".utf8), encoding: "quoted-printable")
        XCTAssertEqual(String(data: qp, encoding: .utf8), "Grüße Welt = ok")
        let b64 = MIMEDecode.transfer(Data("SGFs\r\nbG8=".utf8), encoding: "base64")
        XCTAssertEqual(String(data: b64, encoding: .utf8), "Hallo")
        XCTAssertEqual(MIMEDecode.string(Data([0x47, 0x72, 0xFC, 0xDF, 0x65]), charset: "ISO-8859-1"), "Grüße")
        XCTAssertEqual(MIMEDecode.header("=?utf-8?B?SMOkbGxv?= =?utf-8?B?IFdlbHQ=?="), "Hällo Welt")
    }

    func testModifiedUTF7AndSets() {
        XCTAssertEqual(ModifiedUTF7.decode("[Gmail]/Entw&APw-rfe"), "[Gmail]/Entwürfe")
        XCTAssertEqual(ModifiedUTF7.encode("Entwürfe"), "Entw&APw-rfe")
        XCTAssertEqual(IMAPSet.compress([5, 1, 2, 3, 9, 10]), "1:3,5,9:10")
        XCTAssertEqual(IMAPSet.expand("1:3,5"), [1, 2, 3, 5])
    }

    func testMimeBuilderAndAddresses() {
        let mail = OutgoingMail(from: "a@b.de", to: "\"Müller, Hans\" <hans@x.de>, eva@y.de", subject: "Grüße",
                                text: "Zeile 1\n.Zeile 2", html: "<p>Hallo</p>",
                                attachments: [.init(name: "Datei ä.txt", mime: "text/plain", data: Data("x".utf8))])
        XCTAssertEqual(mail.allRecipients, ["hans@x.de", "eva@y.de"])
        let (data, mid) = MIMEBuilder.build(mail)
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(mid.hasPrefix("<") && mid.hasSuffix("@b.de>"))
        XCTAssertTrue(text.contains("Subject: =?UTF-8?B?"))
        XCTAssertTrue(text.contains("multipart/mixed"))
        XCTAssertTrue(text.contains("multipart/alternative"))
        XCTAssertTrue(text.contains("filename*=UTF-8''"))
    }

    func testHTMLText() {
        let html = "<html><head><style>p{color:red}</style></head><body><p>Hallo&nbsp;Welt</p><ul><li>Eins</li></ul>&euro; &#252;</body></html>"
        let t = HTMLText.visibleText(html)
        XCTAssertFalse(t.contains("color"))
        XCTAssertTrue(t.contains("Hallo Welt"))
        XCTAssertTrue(t.contains("• Eins"))
        XCTAssertTrue(t.contains("€ ü"))
    }

    func testPhishing() {
        let r = PhishingCheck.analyze(fromName: "PayPal Service", fromAddress: "service@paypa1-secure.xyz",
                                      subject: "Konto gesperrt", html: "<a href=\"http://1.2.3.4/login\">www.paypal.com</a>",
                                      text: "Ihr Konto wurde gesperrt. Verifizieren Sie innerhalb von 24 Stunden.")
        XCTAssertTrue(r.suspicious)
    }
}
