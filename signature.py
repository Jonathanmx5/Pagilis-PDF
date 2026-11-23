# SPDX-License-Identifier: AGPL-3.0-or-later
"""Créer sa signature : la tracer à la main, ou écrire son nom dans une police manuscrite.

Le résultat est une image PNG à fond transparent, rognée au plus près, gardée
dans le dossier de données pour être reprise à l'ouverture suivante.
"""

from __future__ import annotations

import math
import os
import time
import tkinter as tk
from typing import Callable

import customtkinter as ctk
from PIL import Image, ImageDraw, ImageFont

import identite
import theme

NOM_FICHIER = "signature.png"      # ancienne signature unique, reprise dans la bibliothèque
DOSSIER = "signatures"            # bibliothèque : signature, initiales, tampons…
ECHELLE = 3          # l'image est dessinée 3 fois plus fine que l'écran : nette à l'impression
MARGE = 6            # marge autour du tracé, en pixels écran
EPAISSEUR = 3        # épaisseur du trait, en pixels écran (aperçu de l'onglet Écrire)
# Plume biseautée : un bec de LARGEUR_BEC pixels tenu à ANGLE_BEC degrés (vers le haut à
# droite). Un trait dans le sens du bec est un délié, un trait en travers est un plein.
LARGEUR_BEC = 7
ANGLE_BEC = 40
DELIE = 1            # épaisseur minimale d'un délié, en pixels écran
AMINCI = 4           # nombre de points sur lesquels le trait s'affine en début et en fin
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


def _lisser(trait: list[tuple[float, float]], passes: int = 2) -> list[tuple[float, float]]:
    """Adoucit le tracé de la souris (coins coupés de Chaikin), extrémités conservées."""
    for _ in range(passes):
        if len(trait) < 3:
            return trait
        lisse = [trait[0]]
        for (x0, y0), (x1, y1) in zip(trait, trait[1:]):
            lisse += [(0.75 * x0 + 0.25 * x1, 0.75 * y0 + 0.25 * y1), (0.25 * x0 + 0.75 * x1, 0.25 * y0 + 0.75 * y1)]
        trait = lisse + [trait[-1]]
    return trait


def polygones_plume(trait: list[tuple[float, float]], echelle: float = 1) -> list[list[tuple[float, float]]]:
    """Formes laissées par la plume le long du trait (coordonnées multipliées par echelle).

    Chaque segment est la surface balayée par le bec ; le bec s'affine aux deux bouts
    du trait, comme une plume qu'on pose puis qu'on lève.
    """
    if not trait:
        return []
    angle = math.radians(ANGLE_BEC)
    bec = (math.cos(angle) * LARGEUR_BEC / 2 * echelle, -math.sin(angle) * LARGEUR_BEC / 2 * echelle)
    pts = [(x * echelle, y * echelle) for x, y in trait]
    if len(pts) == 1:  # un simple clic : une goutte d'encre en forme de bec
        (x, y), (bx, by) = pts[0], bec
        return [[(x + bx, y + by), (x - by / 2, y + bx / 2), (x - bx, y - by), (x + by / 2, y - bx / 2)]]
    n = len(pts)

    def demi_bec(i):
        f = min(1.0, 0.35 + 0.65 * min(i, n - 1 - i) / AMINCI)
        return bec[0] * f, bec[1] * f
    formes = []
    for i in range(n - 1):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        (ax, ay), (bx, by) = demi_bec(i), demi_bec(i + 1)
        formes.append([(x0 + ax, y0 + ay), (x1 + bx, y1 + by), (x1 - bx, y1 - by), (x0 - ax, y0 - ay)])
    return formes


def image_trace(traits: list[list[tuple[float, float]]], encre: tuple[int, int, int]) -> Image.Image | None:
    """Image des traits tracés à la plume (coordonnées écran), ou None s'il n'y a rien."""
    traits = [[(x + MARGE, y + MARGE) for x, y in t] for t in traits if t]
    if not traits:
        return None
    points = [p for trait in traits for p in trait]
    fin = 2 * ECHELLE  # dessiné deux fois plus fin, puis réduit : bords lissés
    largeur = int((max(x for x, _y in points) + MARGE + LARGEUR_BEC) * fin)
    hauteur = int((max(y for _x, y in points) + MARGE + LARGEUR_BEC) * fin)
    masque = Image.new("L", (largeur, hauteur), 0)
    dessin = ImageDraw.Draw(masque)
    for trait in traits:
        trait = _lisser(trait)
        for forme in polygones_plume(trait, fin):
            dessin.polygon(forme, fill=255)
        if len(trait) > 1:  # le délié garde toujours un filet d'encre
            dessin.line([(x * fin, y * fin) for x, y in trait], fill=255, width=DELIE * fin)
    masque = masque.resize((largeur // 2, hauteur // 2), Image.LANCZOS)
    image = Image.new("RGBA", masque.size, encre + (255,))
    image.putalpha(masque)
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


def _bibliotheque(dossier: str) -> str:
    """Dossier des signatures ; l'ancienne signature unique y est reprise une fois."""
    biblio = os.path.join(dossier, DOSSIER)
    os.makedirs(biblio, exist_ok=True)
    ancienne = os.path.join(dossier, NOM_FICHIER)
    if os.path.isfile(ancienne):
        os.replace(ancienne, _nouveau_chemin(biblio, os.path.getmtime(ancienne)))
    return biblio


def _nouveau_chemin(biblio: str, quand: float | None = None) -> str:
    base = int((quand if quand is not None else time.time()) * 1000)
    while os.path.exists(os.path.join(biblio, f"{base}.png")):
        base += 1
    return os.path.join(biblio, f"{base}.png")


def liste(dossier: str) -> list[str]:
    """Les signatures gardées, la plus récente d'abord."""
    biblio = _bibliotheque(dossier)
    chemins = [os.path.join(biblio, n) for n in os.listdir(biblio) if n.lower().endswith(".png")]
    return sorted(chemins, key=lambda c: (os.path.getmtime(c), c), reverse=True)


def enregistrer(image: Image.Image, dossier: str) -> str:
    """Ajoute une signature à la bibliothèque ; retourne son chemin."""
    chemin = _nouveau_chemin(_bibliotheque(dossier))
    image.save(chemin + ".part", format="PNG")
    os.replace(chemin + ".part", chemin)
    return chemin


def importer(source: str, dossier: str) -> str:
    """Copie une image (signature scannée, tampon) dans la bibliothèque. OSError si illisible."""
    with Image.open(source) as im:
        image = im.convert("RGBA")
    return enregistrer(image, dossier)


def supprimer(chemin: str) -> None:
    try:
        os.remove(chemin)
    except FileNotFoundError:
        pass


def signature_enregistree(dossier: str) -> str | None:
    """La dernière signature gardée, s'il y en a une."""
    return next(iter(liste(dossier)), None)


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
        ctk.CTkLabel(self, text="Elle sera gardée sur ce PC avec vos autres signatures (initiales, tampon…).",
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
        self.zone.bind("<ButtonRelease-1>", self._fin_trait)
        bas = ctk.CTkFrame(dessiner, fg_color="transparent")
        bas.pack(fill="x")
        ctk.CTkLabel(bas, text="Signez dans le cadre : le trait imite une plume (pleins et déliés).",
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
        self._dessiner_formes(polygones_plume(self.traits[-1]), "en_cours")

    def _suite_trait(self, evenement):
        if not self.traits:
            return self._debut_trait(evenement)
        self.traits[-1].append((evenement.x, evenement.y))
        self.zone.delete("en_cours")
        self._dessiner_formes(polygones_plume(self.traits[-1]), "en_cours")

    def _fin_trait(self, _evenement=None):
        """Trait terminé : redessiné lissé, comme il sera enregistré."""
        if self.traits:
            self.zone.delete("en_cours")
            self._dessiner_formes(polygones_plume(_lisser(self.traits[-1])), "fini")

    def _dessiner_formes(self, formes, etat: str):
        couleur = self._couleur()
        for forme in formes:
            self.zone.create_polygon(*[c for p in forme for c in p], fill=couleur, outline=couleur,
                                     width=DELIE, tags=("encre", etat))

    def effacer(self):
        self.traits.clear()
        self.zone.delete("encre")

    def _changer_encre(self):
        self.zone.itemconfigure("encre", fill=self._couleur(), outline=self._couleur())
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


class FenetreMesSignatures(ctk.CTkToplevel):
    """« Mes signatures » : les signatures gardées (signature, initiales, tampon…).

    Ouverte depuis l'accueil (sans PDF) pour les gérer, ou depuis « Signer » pour
    en choisir une : `choisir` reçoit alors le chemin de l'image cliquée.
    """

    COLONNES, VIGNETTE = 3, (190, 84)

    def __init__(self, parent, dossier: str, choisir: Callable[[str], None] | None = None,
                 titre: str = "Mes signatures"):
        super().__init__(parent)
        self.dossier, self.choisir = dossier, choisir
        self.title(f"{titre} — {identite.NOM_AFFICHE}")
        theme.poser_icone(self)
        self.configure(fg_color=theme.FOND)
        self.geometry("700x520")
        self.minsize(640, 420)
        self.transient(parent)
        self._images = []

        ctk.CTkLabel(self, text=titre, font=theme.police(18, gras=True),
                     text_color=theme.TEXTE).pack(anchor="w", padx=24, pady=(20, 2))
        aide = ("Cliquez sur une signature pour l'utiliser." if choisir else
                "Gardez ici votre signature, vos initiales, un tampon… Elles sont proposées à chaque signature.")
        ctk.CTkLabel(self, text=aide, font=theme.police(12), text_color=theme.TEXTE_2).pack(anchor="w", padx=24)
        self.grille = ctk.CTkScrollableFrame(self, fg_color=theme.SURFACE, corner_radius=8)
        self.grille.pack(fill="both", expand=True, padx=24, pady=12)
        for c in range(self.COLONNES):
            self.grille.grid_columnconfigure(c, weight=1)

        pied = ctk.CTkFrame(self, fg_color="transparent")
        pied.pack(fill="x", padx=24, pady=(0, 20))
        theme.bouton(pied, "Nouvelle signature…", self.nouvelle, style="principal", icone_nom="signer").pack(side="left")
        theme.bouton(pied, "Importer une image…", self.importer, icone_nom="ouvrir").pack(side="left", padx=8)
        theme.bouton(pied, "Fermer", self.destroy, style="secondaire").pack(side="right")
        self.remplir()
        self.after(50, self.grab_set)

    def remplir(self):
        for enfant in self.grille.winfo_children():
            enfant.destroy()
        self._images.clear()
        chemins = liste(self.dossier)
        if not chemins:
            ctk.CTkLabel(self.grille, text="Aucune signature pour l'instant.\nCréez-en une, ou importez "
                         "une image (signature scannée, tampon).", font=theme.police(13),
                         text_color=theme.TEXTE_2, justify="center").grid(row=0, column=0, columnspan=self.COLONNES,
                                                                         pady=60)
            return
        for i, chemin in enumerate(chemins):
            case = ctk.CTkFrame(self.grille, fg_color=theme.SURFACE_2, corner_radius=8)
            case.grid(row=i // self.COLONNES, column=i % self.COLONNES, padx=6, pady=6, sticky="nsew")
            try:
                with Image.open(chemin) as im:
                    fond = Image.new("RGBA", im.size, "white")
                    fond.alpha_composite(im.convert("RGBA"))
            except OSError:
                continue
            fond.thumbnail(self.VIGNETTE)
            image = ctk.CTkImage(fond, size=fond.size)
            self._images.append(image)
            vignette = ctk.CTkButton(case, text="", image=image, fg_color="white", hover_color="#dfe7fb",
                                     width=self.VIGNETTE[0] + 12, height=self.VIGNETTE[1] + 12, corner_radius=6,
                                     command=(lambda c=chemin: self._utiliser(c)) if self.choisir else None)
            vignette.pack(padx=8, pady=(8, 4))
            theme.bouton(case, "Supprimer", lambda c=chemin: self._supprimer(c), style="discret",
                         icone_nom="supprimer", hauteur=26).pack(pady=(0, 6))

    def _utiliser(self, chemin: str):
        self.destroy()
        self.choisir(chemin)

    def _supprimer(self, chemin: str):
        from tkinter import messagebox
        if messagebox.askyesno(identite.NOM_AFFICHE, "Supprimer cette signature ?", parent=self):
            supprimer(chemin)
            self.remplir()

    def nouvelle(self):
        FenetreSignature(self, self.dossier, lambda chemin: self._apres_ajout(chemin))

    def importer(self):
        from tkinter import filedialog, messagebox
        source = filedialog.askopenfilename(parent=self, filetypes=[("Images", "*.png *.jpg *.jpeg")])
        if not source:
            return
        try:
            chemin = importer(source, self.dossier)
        except OSError:
            return messagebox.showerror(identite.NOM_AFFICHE, "Image illisible.", parent=self)
        self._apres_ajout(chemin)

    def _apres_ajout(self, chemin: str):
        if self.choisir:
            return self._utiliser(chemin)
        self.remplir()
        self.grab_set()
