# SPDX-License-Identifier: AGPL-3.0-or-later
"""Cases de signature : « Signer ici : Client », à préparer puis à remplir.

Une case est un vrai champ de signature PDF : les autres lecteurs (Adobe, Aperçu,
navigateurs) la montrent et la proposent à la signature. Son apparence enregistrée
affiche « Signer ici : nom » en français.

Signer une case avec une image : l'image est ajustée au cadre (sans déformation)
et la case vide disparaît. Coordonnées en points, repère de la page AFFICHÉE.
Aucune interface ici : le module est testable sans écran.
"""

from __future__ import annotations

from dataclasses import dataclass

import pymupdf as fitz
from PIL import Image

import pdf_core as core

LARGEUR_MIN, HAUTEUR_MIN = 20, 10


@dataclass
class Case:
    nom: str
    rect: fitz.Rect   # repère affiché
    xref: int


def _vers_page(page: fitz.Page, rect: fitz.Rect) -> fitz.Rect:
    """Rectangle du repère affiché vers le repère non tourné de la page."""
    r = fitz.Rect(rect) * page.derotation_matrix
    r.normalize()
    return r


def cases_vides(page: fitz.Page) -> list[Case]:
    """Cases de signature pas encore signées, dans l'ordre de la page."""
    resultat = []
    for w in page.widgets(types=[fitz.PDF_WIDGET_TYPE_SIGNATURE]) or []:
        if w.is_signed:
            continue
        resultat.append(Case(w.field_name or "", core.to_displayed(page, w.rect), w.xref))
    return resultat


def case_en(page: fitz.Page, point: fitz.Point) -> Case | None:
    return next((c for c in cases_vides(page) if c.rect.contains(point)), None)


def _noms(doc: fitz.Document) -> set[str]:
    return {w.field_name for page in doc for w in (page.widgets() or [])}


def _apparence(doc: fitz.Document, largeur: float, hauteur: float, texte: str) -> int:
    """Forme XObject « Signer ici : nom » : fond bleuté, cadre bleu, ligne grise."""
    taille = max(6.0, min(10.0, hauteur / 4))
    octets = texte.encode("cp1252", "replace").hex().upper()
    contenu = (
        f"q 0.941 0.961 1 rg 0 0 {largeur:.2f} {hauteur:.2f} re f "
        f"0.24 0.48 0.96 RG 1 w 0.5 0.5 {largeur - 1:.2f} {hauteur - 1:.2f} re S "
        f"0.6 0.62 0.66 RG 0.75 w 6 {taille + 9:.2f} m {largeur - 6:.2f} {taille + 9:.2f} l S "
        f"BT /Helv {taille:.1f} Tf 0.3 0.35 0.45 rg 6 5 Td <{octets}> Tj ET Q"
    ).encode("latin-1")
    xref = doc.get_new_xref()
    doc.update_object(xref, (
        f"<< /Type /XObject /Subtype /Form /BBox [0 0 {largeur:.2f} {hauteur:.2f}] "
        "/Resources << /Font << /Helv << /Type /Font /Subtype /Type1 /BaseFont /Helvetica "
        "/Encoding /WinAnsiEncoding >> >> >> >>"))
    doc.update_stream(xref, contenu)
    return xref


def creer_case(doc: fitz.Document, index: int, rect: fitz.Rect, nom: str) -> bool:
    """Ajoute une case à signer pour `nom`. Faux si le cadre est trop petit."""
    rect = fitz.Rect(rect)
    rect.normalize()
    if rect.width < LARGEUR_MIN or rect.height < HAUTEUR_MIN:
        return False
    base = nom.strip() or "Signature"
    noms, unique, n = _noms(doc), base, 2
    while unique in noms:
        unique, n = f"{base} {n}", n + 1
    page = doc[index]
    w = fitz.Widget()
    w.field_type = fitz.PDF_WIDGET_TYPE_SIGNATURE
    w.field_name = unique
    w.rect = _vers_page(page, rect)
    w.border_color = (0.24, 0.48, 0.96)
    page.add_widget(w)
    r = w.rect
    ap = _apparence(doc, r.width, r.height, f"Signer ici : {unique}")
    doc.xref_set_key(w.xref, "AP", f"<< /N {ap} 0 R >>")
    return True


def signer_case(doc: fitz.Document, index: int, xref: int, image: str) -> bool:
    """Pose l'image dans la case (ajustée, centrée) et retire la case vide."""
    page = doc[index]
    case = next((c for c in cases_vides(page) if c.xref == xref), None)
    if case is None:
        return False
    with Image.open(image) as im:
        ratio = im.height / im.width
    cadre = case.rect
    largeur = cadre.width * 0.96
    hauteur = largeur * ratio
    if hauteur > cadre.height * 0.96:
        hauteur = cadre.height * 0.96
        largeur = hauteur / ratio
    x = cadre.x0 + (cadre.width - largeur) / 2
    y = cadre.y0 + (cadre.height - hauteur) / 2
    widget = next(w for w in page.widgets() if w.xref == xref)
    page.delete_widget(widget)
    core.add_stamp(doc, image, [index], core.StampOptions(x, y, largeur))
    return True


def nombre_vides(doc: fitz.Document) -> int:
    return sum(len(cases_vides(page)) for page in doc)

