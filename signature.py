# SPDX-License-Identifier: AGPL-3.0-or-later
"""Créer sa signature : la tracer à la main, ou écrire son nom dans une police manuscrite.

Le résultat est une image PNG à fond transparent, rognée au plus près, gardée
dans le dossier de données pour être reprise à l'ouverture suivante.
"""

from __future__ import annotations

import os
import tkinter as tk
from typing import Callable

import customtkinter as ctk
from PIL import Image, ImageDraw, ImageFont

import identite
import theme

NOM_FICHIER = "signature.png"
ECHELLE = 3          # l'image est dessinée 3 fois plus fine que l'écran : nette à l'impression
MARGE = 6            # marge autour du tracé, en pixels écran
EPAISSEUR = 3        # épaisseur du trait, en pixels écran
ENCRES = {"Noire": (25, 25, 35), "Bleue": (20, 50, 140)}
DOSSIER_POLICES = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
# Polices manuscrites livrées avec Windows ou Office, de la plus lisible à la plus ornée.
POLICES_MANUSCRITES = [
    ("Segoe Script", "segoesc.ttf"),
    ("Ink Free", "Inkfree.ttf"),
    ("Lucida Handwriting", "LHANDW.TTF"),
    ("Brush Script", "BRUSHSCI.TTF"),
    ("French Script", "FRSCRIPT.TTF"),
]


def polices_disponibles(dossier: str = DOSSIER_POLICES) -> list[tuple[str, str]]:
    """Les polices manuscrites présentes sur ce PC : (nom affiché, chemin)."""
    return [(nom, os.path.join(dossier, fichier)) for nom, fichier in POLICES_MANUSCRITES
            if os.path.isfile(os.path.join(dossier, fichier))]


def _rogner(image: Image.Image, marge: int) -> Image.Image | None:
    boite = image.getchannel("A").getbbox()
    if boite is None:
        return None
    x0, y0, x1, y1 = boite
    return image.crop((max(0, x0 - marge), max(0, y0 - marge),
                       min(image.width, x1 + marge), min(image.height, y1 + marge)))


def image_trace(traits: list[list[tuple[float, float]]], encre: tuple[int, int, int],
                epaisseur: int = EPAISSEUR) -> Image.Image | None:
    """Image des traits tracés (coordonnées écran), ou None s'il n'y a rien."""
    points = [p for trait in traits for p in trait]
    if not points:
        return None
    largeur = int(max(x for x, _y in points) + 2 * MARGE + epaisseur) * ECHELLE
    hauteur = int(max(y for _x, y in points) + 2 * MARGE + epaisseur) * ECHELLE
    image = Image.new("RGBA", (largeur, hauteur), (0, 0, 0, 0))
    dessin = ImageDraw.Draw(image)
    couleur, rayon = encre + (255,), epaisseur * ECHELLE / 2
    for trait in traits:
        pts = [((x + MARGE) * ECHELLE, (y + MARGE) * ECHELLE) for x, y in trait]
        if len(pts) > 1:
            dessin.line(pts, fill=couleur, width=epaisseur * ECHELLE)
        for x, y in pts:  # extrémités et angles arrondis ; un simple clic fait un point
            dessin.ellipse((x - rayon, y - rayon, x + rayon, y + rayon), fill=couleur)
    return _rogner(image, MARGE * ECHELLE)


def image_texte(texte: str, chemin_police: str, encre: tuple[int, int, int],
                taille: int = 160) -> Image.Image | None:
    """Le nom écrit dans la police manuscrite, ou None si le texte est vide."""
    texte = texte.strip()
    if not texte:
        return None
    police = ImageFont.truetype(chemin_police, taille)
    x0, y0, x1, y1 = police.getbbox(texte)
    image = Image.new("RGBA", (x1 - x0 + taille, y1 - y0 + taille), (0, 0, 0, 0))
    ImageDraw.Draw(image).text((taille / 2 - x0, taille / 2 - y0), texte, font=police, fill=encre + (255,))
    return _rogner(image, taille // 10)


def enregistrer(image: Image.Image, dossier: str) -> str:
    os.makedirs(dossier, exist_ok=True)
    chemin = os.path.join(dossier, NOM_FICHIER)
    image.save(chemin)
    return chemin


def signature_enregistree(dossier: str) -> str | None:
    chemin = os.path.join(dossier, NOM_FICHIER)
    return chemin if os.path.isfile(chemin) else None


class FenetreSignature(ctk.CTkToplevel):
    """« Créer ma signature » : onglets Dessiner et Écrire, encre, Valider."""

    LARGEUR, HAUTEUR = 560, 200

    def __init__(self, parent, dossier: str, a_la_fin: Callable[[str], None]):
        super().__init__(parent)
        self.dossier, self.a_la_fin = dossier, a_la_fin
        self.title(f"Créer ma signature — {identite.NOM_AFFICHE}")
        theme.poser_icone(self)
        self.configure(fg_color=theme.FOND)
        self.geometry("760x620")
        self.resizable(False, False)
        self.transient(parent)
        self.traits: list[list[tuple[float, float]]] = []
        self.polices = polices_disponibles()

        ctk.CTkLabel(self, text="Créer ma signature", font=theme.police(18, gras=True),
                     text_color=theme.TEXTE).pack(anchor="w", padx=24, pady=(20, 2))
        ctk.CTkLabel(self, text="Elle sera gardée sur ce PC et proposée à chaque signature.",
                     font=theme.police(12), text_color=theme.TEXTE_2).pack(anchor="w", padx=24)

        self.onglets = ctk.CTkTabview(self, fg_color=theme.SURFACE, height=290,
                                      segmented_button_selected_color=theme.ACCENT,
                                      command=self._apercu_texte)
        self.onglets.pack(fill="x", padx=24, pady=(12, 0))
        dessiner, ecrire = self.onglets.add("Dessiner"), self.onglets.add("Écrire")

        # --- Dessiner : souris, doigt ou stylet
        self.zone = tk.Canvas(dessiner, width=self.LARGEUR, height=self.HAUTEUR, bg="white",
                              highlightthickness=0, cursor="pencil")
        self.zone.pack(pady=(4, 6))
        self.zone.create_line(30, self.HAUTEUR - 45, self.LARGEUR - 30, self.HAUTEUR - 45,
                              fill="#D0D4DA", dash=(4, 4), tags="guide")
        self.zone.bind("<ButtonPress-1>", self._debut_trait)
        self.zone.bind("<B1-Motion>", self._suite_trait)
        bas = ctk.CTkFrame(dessiner, fg_color="transparent")
        bas.pack(fill="x")
        ctk.CTkLabel(bas, text="Signez dans le cadre, à la souris, au doigt ou au stylet.",
                     font=theme.police(12), text_color=theme.TEXTE_2).pack(side="left")
        theme.bouton(bas, "Effacer", self.effacer, style="discret", icone_nom="supprimer").pack(side="right")

        # --- Écrire : nom dans une police manuscrite
        self.nom = ctk.StringVar()
        entree = ctk.CTkEntry(ecrire, textvariable=self.nom, placeholder_text="Votre nom",
                              height=36, font=theme.police(14))
        entree.pack(fill="x", padx=8, pady=(8, 8))
        self.nom.trace_add("write", lambda *_a: self._apercu_texte())
        noms = [n for n, _c in self.polices] or ["(aucune police manuscrite)"]
        self.police = ctk.StringVar(value=noms[0])
        ctk.CTkSegmentedButton(ecrire, values=noms, variable=self.police, selected_color=theme.ACCENT,
                               command=lambda _v: self._apercu_texte()).pack(fill="x", padx=8)
        self.apercu = ctk.CTkLabel(ecrire, text="", height=130, fg_color="white", corner_radius=6)
        self.apercu.pack(fill="x", padx=8, pady=8)

        # --- Encre et boutons
        pied = ctk.CTkFrame(self, fg_color="transparent")
        pied.pack(fill="x", padx=24, pady=(10, 20))
        ctk.CTkLabel(pied, text="Encre", font=theme.police(13), text_color=theme.TEXTE).pack(side="left")
        self.encre = ctk.StringVar(value="Bleue")
        ctk.CTkSegmentedButton(pied, values=list(ENCRES), variable=self.encre, selected_color=theme.ACCENT,
                               command=lambda _v: self._changer_encre()).pack(side="left", padx=10)
        theme.bouton(pied, "Utiliser cette signature", self.valider, style="principal",
                     icone_nom="valider").pack(side="right")
        theme.bouton(pied, "Annuler", self.destroy, style="secondaire").pack(side="right", padx=8)
        self.message = ctk.CTkLabel(self, text="", font=theme.police(12), text_color=theme.DANGER)
        self.message.pack(anchor="w", padx=24, pady=(0, 10))

        self.after(50, self.grab_set)

    # ------------------------------------------------------- dessin

    def _couleur(self) -> str:
        return "#%02x%02x%02x" % ENCRES[self.encre.get()]

    def _debut_trait(self, evenement):
        self.traits.append([(evenement.x, evenement.y)])
        r = EPAISSEUR / 2
        self.zone.create_oval(evenement.x - r, evenement.y - r, evenement.x + r, evenement.y + r,
                              fill=self._couleur(), outline="", tags="encre")

    def _suite_trait(self, evenement):
        if not self.traits:
            return self._debut_trait(evenement)
        x0, y0 = self.traits[-1][-1]
        self.traits[-1].append((evenement.x, evenement.y))
        self.zone.create_line(x0, y0, evenement.x, evenement.y, fill=self._couleur(), width=EPAISSEUR,
                              capstyle="round", smooth=True, tags="encre")

    def effacer(self):
        self.traits.clear()
        self.zone.delete("encre")

    def _changer_encre(self):
        self.zone.itemconfigure("encre", fill=self._couleur())
        self._apercu_texte()

    # ------------------------------------------------------- texte

    def _chemin_police(self) -> str | None:
        return dict(self.polices).get(self.police.get())

    def _image_texte(self) -> Image.Image | None:
        chemin = self._chemin_police()
        return image_texte(self.nom.get(), chemin, ENCRES[self.encre.get()]) if chemin else None

    def _apercu_texte(self, *_a):
        image = self._image_texte()
        if image is None:
            self.apercu.configure(image=None, text="")
            return
        fond = Image.new("RGBA", image.size, "white")
        fond.alpha_composite(image)
        fond.thumbnail((self.LARGEUR - 40, 110))
        self._image_apercu = ctk.CTkImage(fond, size=fond.size)
        self.apercu.configure(image=self._image_apercu, text="")

    # ------------------------------------------------------- résultat

    def image(self) -> Image.Image | None:
        if self.onglets.get() == "Dessiner":
            return image_trace(self.traits, ENCRES[self.encre.get()])
        return self._image_texte()

    def valider(self):
        image = self.image()
        if image is None:
            vide = "Signez d'abord dans le cadre." if self.onglets.get() == "Dessiner" else "Écrivez d'abord votre nom."
            self.message.configure(text=vide)
            return
        chemin = enregistrer(image, self.dossier)
        self.destroy()
        self.a_la_fin(chemin)
