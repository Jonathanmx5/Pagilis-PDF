# SPDX-License-Identifier: AGPL-3.0-or-later
"""Création d'une signature : tracé à la main ou nom dans une police manuscrite."""

import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import signature as sg  # noqa: E402


def test_trace_gives_cropped_transparent_image_in_ink_colour():
    traits = [[(10, 50), (60, 20), (110, 60)], [(30, 80), (100, 80)]]
    image = sg.image_trace(traits, sg.ENCRES["Bleue"])
    assert image.mode == "RGBA"
    assert image.getpixel((0, 0))[3] == 0, "fond transparent"
    # Rognée au tracé (plus une marge) : bien plus petite qu'une zone de dessin entière.
    largeur, hauteur = image.size
    assert largeur > hauteur and largeur < 110 * sg.ECHELLE + 2 * sg.MARGE * sg.ECHELLE + 10
    encre = [p for p in (image.getpixel((x, y)) for x in range(image.width) for y in range(image.height))
             if p[3] == 255]
    assert encre and all(p[:3] == sg.ENCRES["Bleue"] for p in encre)


def test_single_click_draws_a_dot_and_empty_trace_gives_nothing():
    assert sg.image_trace([], sg.ENCRES["Noire"]) is None
    assert sg.image_trace([[]], sg.ENCRES["Noire"]) is None
    point = sg.image_trace([[(40, 40)]], sg.ENCRES["Noire"])
    assert point is not None and point.size[0] > 0


def test_text_signature_uses_handwriting_font():
    polices = sg.polices_disponibles()
    assert polices, "Windows fournit au moins une police manuscrite"
    nom, chemin = polices[0]
    image = sg.image_texte("Jean Dupont", chemin, sg.ENCRES["Noire"])
    assert image.mode == "RGBA" and image.size[0] > image.size[1] > 0
    assert image.getpixel((0, 0))[3] == 0
    assert sg.image_texte("   ", chemin, sg.ENCRES["Noire"]) is None


def test_missing_fonts_are_skipped(tmp_path):
    (tmp_path / "Inkfree.ttf").write_bytes(b"")
    assert [n for n, _c in sg.polices_disponibles(str(tmp_path))] == ["Ink Free"]


def test_save_and_reload(tmp_path):
    image = sg.image_trace([[(0, 0), (50, 20)]], sg.ENCRES["Noire"])
    chemin = sg.enregistrer(image, str(tmp_path / "sous" / "dossier"))
    assert os.path.basename(chemin) == sg.NOM_FICHIER and os.path.isfile(chemin)
    assert sg.signature_enregistree(str(tmp_path / "sous" / "dossier")) == chemin
    assert sg.signature_enregistree(str(tmp_path / "vide")) is None


def _encre(image):
    return sum(image.getchannel("A").histogram()[129:])


def test_fountain_pen_has_thick_downstrokes_and_thin_hairlines():
    # Plume biseautée : un trait dans le sens du bec (vers le haut à droite) est un délié,
    # un trait en travers (vers le bas à droite) est un plein.
    delie = sg.image_trace([[(0, 100), (100, 0)]], sg.ENCRES["Noire"])
    plein = sg.image_trace([[(0, 0), (100, 100)]], sg.ENCRES["Noire"])
    assert _encre(plein) > 2.5 * _encre(delie)


def test_pen_edges_are_smooth():
    image = sg.image_trace([[(0, 50), (40, 10), (80, 60), (120, 20)]], sg.ENCRES["Bleue"])
    assert sum(image.getchannel("A").histogram()[1:255]), "bords lissés (anticrénelage)"


def test_live_preview_polygons_follow_the_stroke():
    assert sg.polygones_plume([(10, 10)]), "un simple clic laisse une goutte d'encre"
    polygones = sg.polygones_plume([(10, 10), (30, 30), (60, 20)])
    assert polygones and all(len(p) >= 4 for p in polygones)
