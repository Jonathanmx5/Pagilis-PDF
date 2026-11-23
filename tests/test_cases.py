# SPDX-License-Identifier: AGPL-3.0-or-later
"""Cases de signature : créées pour quelqu'un d'autre, remplies par soi-même."""

import os
import sys

import pymupdf as fitz
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import cases  # noqa: E402


def _doc():
    doc = fitz.open()
    doc.new_page(width=595, height=842)
    return doc


def _signature(tmp_path):
    image = Image.new("RGBA", (400, 100), (0, 0, 0, 0))
    ImageDraw.Draw(image).rectangle((20, 20, 380, 80), fill=(0, 0, 0, 255))
    chemin = tmp_path / "sig.png"
    image.save(chemin)
    return str(chemin)


def _relu(doc):
    return fitz.open("pdf", doc.tobytes())


def test_case_creee_visible_dans_les_autres_lecteurs():
    doc = _doc()
    assert cases.creer_case(doc, 0, fitz.Rect(320, 700, 520, 760), "Client")
    assert cases.creer_case(doc, 0, fitz.Rect(60, 700, 260, 760), "Client")  # même nom : rendu unique
    assert not cases.creer_case(doc, 0, fitz.Rect(10, 10, 12, 12), "X"), "trop petite"
    relu = _relu(doc)
    vides = cases.cases_vides(relu[0])
    assert sorted(c.nom for c in vides) == ["Client", "Client 2"]
    client = next(c for c in vides if c.nom == "Client")
    assert abs(client.rect.x0 - 320) < 1 and abs(client.rect.y1 - 760) < 1
    assert relu.is_form_pdf


def test_apparence_enregistree_en_francais():
    doc = _doc()
    cases.creer_case(doc, 0, fitz.Rect(320, 700, 520, 760), "Client")
    relu = _relu(doc)
    xref = cases.cases_vides(relu[0])[0].xref
    ap = relu.xref_get_key(xref, "AP/N")
    flux = relu.xref_stream(int(ap[1].split()[0]))
    assert "Signer ici : Client".encode("cp1252").hex().upper().encode() in flux


def test_case_trouvee_sous_le_clic():
    doc = _doc()
    cases.creer_case(doc, 0, fitz.Rect(320, 700, 520, 760), "Moi")
    assert cases.case_en(doc[0], fitz.Point(420, 730)).nom == "Moi"
    assert cases.case_en(doc[0], fitz.Point(100, 100)) is None


def test_case_remplie_porte_la_signature_et_disparait(tmp_path):
    doc = _doc()
    cases.creer_case(doc, 0, fitz.Rect(320, 700, 520, 760), "Moi")
    cases.creer_case(doc, 0, fitz.Rect(60, 700, 260, 760), "Client")
    moi = next(c for c in cases.cases_vides(doc[0]) if c.nom == "Moi")
    assert cases.signer_case(doc, 0, moi.xref, _signature(tmp_path))
    relu = _relu(doc)
    assert [c.nom for c in cases.cases_vides(relu[0])] == ["Client"], "la case du client reste à signer"
    assert cases.nombre_vides(relu) == 1
    pix = relu[0].get_pixmap()
    # Signature 4:1 ajustée au cadre 200 x 60 : 192 x 48 centrée, donc de 706 à 754 en hauteur.
    assert pix.pixel(420, 730)[0] < 60, "encre au centre de la case"
    assert pix.pixel(420, 702)[0] > 200, "rien au-dessus de la signature ajustée"
    assert not cases.signer_case(relu, 0, 999999, _signature(tmp_path))


def test_page_tournee(tmp_path):
    doc = _doc()
    doc[0].set_rotation(90)
    assert cases.creer_case(doc, 0, fitz.Rect(100, 100, 300, 160), "Client")
    case = cases.cases_vides(doc[0])[0]
    assert abs(case.rect.x0 - 100) < 1 and abs(case.rect.y0 - 100) < 1, "repère affiché conservé"
    assert cases.signer_case(doc, 0, case.xref, _signature(tmp_path))
    assert cases.nombre_vides(doc) == 0
