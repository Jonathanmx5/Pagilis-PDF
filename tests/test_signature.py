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
    dossier = str(tmp_path / "sous" / "dossier")
    chemin = sg.enregistrer(image, dossier)
    assert os.path.isfile(chemin) and os.path.dirname(chemin).endswith(sg.DOSSIER)
    assert sg.signature_enregistree(dossier) == chemin
    assert sg.signature_enregistree(str(tmp_path / "vide")) is None


def test_several_signatures_kept_newest_first(tmp_path):
    dossier = str(tmp_path)
    signature = sg.enregistrer(sg.image_trace([[(0, 0), (50, 20)]], sg.ENCRES["Noire"]), dossier)
    initiales = sg.enregistrer(sg.image_trace([[(0, 0), (10, 20)]], sg.ENCRES["Bleue"]), dossier)
    os.utime(signature, (1, 1))
    assert sg.liste(dossier) == [initiales, signature]
    sg.supprimer(signature)
    sg.supprimer(signature)  # déjà supprimée : sans erreur
    assert sg.liste(dossier) == [initiales]


def test_import_image_and_reject_unreadable(tmp_path):
    from PIL import Image
    tampon = tmp_path / "tampon.jpg"
    Image.new("RGB", (120, 60), "red").save(tampon)
    chemin = sg.importer(str(tampon), str(tmp_path / "donnees"))
    assert chemin.endswith(".png") and sg.liste(str(tmp_path / "donnees")) == [chemin]
    faux = tmp_path / "faux.png"
    faux.write_bytes(b"pas une image")
    try:
        sg.importer(str(faux), str(tmp_path / "donnees"))
        assert False, "image illisible refusée"
    except OSError:
        pass
    assert len(sg.liste(str(tmp_path / "donnees"))) == 1


def test_old_single_signature_moves_into_library(tmp_path):
    ancienne = tmp_path / sg.NOM_FICHIER
    sg.image_trace([[(0, 0), (50, 20)]], sg.ENCRES["Noire"]).save(ancienne)
    gardees = sg.liste(str(tmp_path))
    assert len(gardees) == 1 and not ancienne.exists()


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


def test_names_kept_next_to_image_and_proposed_in_order(tmp_path):
    dossier = str(tmp_path)
    assert sg.nom_propose(dossier) == "Signature"
    a = sg.enregistrer(sg.image_trace([[(0, 0), (50, 20)]], sg.ENCRES["Noire"]), dossier, "Signature")
    assert sg.nom(a) == "Signature" and sg.nom_propose(dossier) == "Initiales"
    sg.nommer(a, "  Paraphe ")
    assert sg.nom(a) == "Paraphe"
    sg.nommer(a, "   ")
    assert sg.nom(a) == "Signature", "nom vide : nom par défaut"
    sg.supprimer(a)
    assert os.listdir(os.path.join(dossier, sg.DOSSIER)) == [], "image et nom supprimés ensemble"


def test_imported_image_named_after_its_file(tmp_path):
    from PIL import Image
    source = tmp_path / "Tampon société.png"
    Image.new("RGB", (80, 40), "red").save(source)
    assert sg.nom(sg.importer(str(source), str(tmp_path / "d"))) == "Tampon société"


def test_thickness_widens_the_pen():
    trait = [[(0, 0), (100, 100)]]
    fin = _encre(sg.image_trace(trait, sg.ENCRES["Noire"], sg.EPAISSEURS["Fin"]))
    epais = _encre(sg.image_trace(trait, sg.ENCRES["Noire"], sg.EPAISSEURS["Épais"]))
    assert epais > 1.5 * fin
