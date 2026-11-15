# SPDX-License-Identifier: AGPL-3.0-or-later
"""Pages : pivoter, supprimer, réordonner, insérer et extraire des pages.

Les vignettes de toutes les pages s'affichent à gauche ; l'aperçu montre la
page cliquée. Les actions retouchent la copie de travail, comme « Modifier ».
"""

from __future__ import annotations

import logging
import os
import tkinter as tk
from tkinter import filedialog, messagebox

import customtkinter as ctk
import pymupdf as fitz
from PIL import Image, ImageTk

import identite
import pdf_core as core
import theme
from pages.copie import PageCopie

CELLULE = (134, 158)  # largeur, hauteur d'une vignette avec son numéro (pixels)
COULEUR_CHOISIE = theme.ACCENT_DOUX


def _plages(indices: list[int]) -> str:
    """[0, 1, 2, 4] -> « 1-3,5 » (numéros de page affichés)."""
    morceaux, debut = [], None
    for k, i in enumerate(indices):
        if debut is None:
            debut = i
        if k + 1 == len(indices) or indices[k + 1] != i + 1:
            morceaux.append(f"{debut + 1}" if debut == i else f"{debut + 1}-{i + 1}")
            debut = None
    return ",".join(morceaux)


class PageGestionPages(PageCopie):
    nom = "Pages"
    libelle = "Pages"
    groupe = "Éditer"
    icone = "pages"
    description = "Pivoter, supprimer, réordonner, insérer ou extraire des pages."

    def construire(self):
        self.selection: set[int] = set()
        self._ancre: int | None = None   # point de départ d'une sélection avec Maj
        self.vignettes: list[ctk.CTkFrame] = []
        self._images: list[ImageTk.PhotoImage] = []
        self._a_redessiner = True
        self.section("Pages sélectionnées")
        self.aide("Clic : une page. Ctrl+clic : en ajouter. Maj+clic : une suite de pages. "
                  "Glissez les vignettes pour changer l'ordre.")
        ligne = self.ligne()
        for icone_nom, miroir, commande, bulle in (
                ("pivoter", True, lambda: self.tourner(-90), "Pivoter à gauche"),
                ("pivoter", False, lambda: self.tourner(90), "Pivoter à droite"),
                ("monter", False, lambda: self.deplacer(-1), "Avancer"),
                ("descendre", False, lambda: self.deplacer(1), "Reculer")):
            theme.bouton(ligne, icone_nom=icone_nom, miroir=miroir, commande=commande, bulle=bulle).pack(side="left", padx=(0, 6))
        theme.bouton(ligne, "Supprimer", self.supprimer, style="danger", icone_nom="supprimer").pack(side="right")
        ligne = self.ligne()
        theme.bouton(ligne, "Insérer un PDF…", self.inserer, icone_nom="inserer").pack(
            side="left", fill="x", expand=True, padx=(0, 6))
        theme.bouton(ligne, "Extraire…", self.extraire, icone_nom="extraire").pack(side="left", fill="x", expand=True)
        self.section("Toutes les pages")
        # Toutes les vignettes sur UNE surface de dessin : des centaines de pages
        # s'affichent en une fraction de seconde (un widget par page prenait des secondes).
        self.echelle = ctk.ScalingTracker.get_widget_scaling(self.cadre)
        self.toile = tk.Canvas(self.cadre, bg=theme.SURFACE, highlightthickness=0, bd=0,
                               width=round(2 * CELLULE[0] * self.echelle), height=10)
        self.toile.pack(padx=10, pady=(0, 12), anchor="w")
        self.toile.bind("<ButtonPress-1>", lambda e: self._appui_toile(e.x, self.toile.canvasy(e.y), e.state))
        self.toile.bind("<B1-Motion>", lambda e: self._glisse_toile(e.x, self.toile.canvasy(e.y)))
        self.toile.bind("<ButtonRelease-1>", lambda e: self._relache_toile(e.x, self.toile.canvasy(e.y)))
        self._geste = None  # (x, y, page sous la souris, clic différé, glisser en cours)
        self._a_actualiser: list[int] | None = None  # pages à redessiner seules, sinon tout

    # ------------------------------------------------------- vignettes

    def montree(self):
        if self._a_redessiner:
            self._dessiner_vignettes()

    def edition_changee(self):
        self._a_redessiner = True
        if self.app.page_active == self.nom:
            pages, self._a_actualiser = self._a_actualiser, None
            if pages is not None and self.edition and len(self.vignettes) == self.edition.doc.page_count:
                self._actualiser_vignettes(pages)
            else:
                self._dessiner_vignettes()

    def document_charge(self):
        self.selection.clear()
        self._ancre = None
        super().document_charge()

    # Cellule d'une vignette (pixels avant mise à l'échelle de l'écran) et côté maximal de l'image.
    def _image_page(self, i: int) -> ImageTk.PhotoImage:
        page = self.edition.doc[i]
        cote = (CELLULE[0] - 24) * self.echelle
        zoom = cote / max(page.rect.width, page.rect.height)
        pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
        return ImageTk.PhotoImage(Image.frombytes("RGB", (pix.width, pix.height), pix.samples))

    def _position(self, i: int) -> tuple[float, float, float, float]:
        largeur, hauteur = CELLULE[0] * self.echelle, CELLULE[1] * self.echelle
        x0, y0 = (i % 2) * largeur, (i // 2) * hauteur
        return x0, y0, x0 + largeur, y0 + hauteur

    def _dessiner_cellule(self, i: int):
        x0, y0, x1, y1 = self._position(i)
        e = self.echelle
        fond = self.toile.create_rectangle(x0 + 3 * e, y0 + 3 * e, x1 - 3 * e, y1 - 3 * e, width=0,
                                           fill=COULEUR_CHOISIE if i in self.selection else theme.SURFACE)
        self.toile.create_image((x0 + x1) / 2, y0 + (y1 - y0 - 20 * e) / 2 + 2 * e, image=self._images[i],
                                tags=f"image{i}")
        self.toile.create_text((x0 + x1) / 2, y1 - 13 * e, text=str(i + 1), fill=theme.TEXTE_2,
                               font=(theme.POLICE, -round(12 * e)))
        return fond

    def _dessiner_vignettes(self):
        """Une vignette par page de la copie de travail, deux par ligne."""
        if self.app.travaux_en_cours:
            return  # PyMuPDF ne doit pas travailler sur deux threads ; on redessinera après
        self.toile.delete("all")
        self.vignettes, self._images = [], []
        self._a_redessiner = False
        doc = self.edition.doc if self.edition else None
        if doc is None:
            self.toile.configure(height=round(40 * self.echelle))
            self.toile.create_text(6, 20 * self.echelle, anchor="w", text="Ouvrez un PDF pour voir ses pages.",
                                   fill=theme.TEXTE_2, font=(theme.POLICE, -round(12 * self.echelle)))
            return
        self.selection = {i for i in self.selection if i < doc.page_count}
        self._images = [self._image_page(i) for i in range(doc.page_count)]
        self.vignettes = [self._dessiner_cellule(i) for i in range(doc.page_count)]
        self.toile.configure(height=self._position(doc.page_count - 1)[3] + 4)

    def _actualiser_vignettes(self, pages: list[int]):
        """Redessine seulement les images de `pages` (après une rotation, par exemple)."""
        if self.app.travaux_en_cours:
            return
        self._a_redessiner = False
        for i in pages:
            self._images[i] = self._image_page(i)
            self.toile.itemconfigure(f"image{i}", image=self._images[i])

    def _marquer_selection(self):
        for i, fond in enumerate(self.vignettes):
            self.toile.itemconfigure(fond, fill=COULEUR_CHOISIE if i in self.selection else theme.SURFACE)

    def _case(self, x: float, y: float) -> int | None:
        """Page sous le point (coordonnées de la surface), ou None."""
        colonne = int(x // (CELLULE[0] * self.echelle))
        i = int(y // (CELLULE[1] * self.echelle)) * 2 + colonne
        return i if colonne in (0, 1) and 0 <= i < len(self.vignettes) else None

    def _centre(self, i: int) -> tuple[float, float]:
        x0, y0, x1, y1 = self._position(i)
        return (x0 + x1) / 2, (y0 + y1) / 2

    def _place(self, x: float, y: float) -> int:
        """Place d'insertion sous le point : avant la page si on est sur sa moitié gauche."""
        n = len(self.vignettes)
        largeur = CELLULE[0] * self.echelle
        colonne = min(1, max(0, int(x // largeur)))
        i = max(0, int(y // (CELLULE[1] * self.echelle))) * 2 + colonne
        if i >= n:
            return n
        return i if x - colonne * largeur < largeur / 2 else i + 1

    def _appui_toile(self, x: float, y: float, etat: int):
        """Appui sur les vignettes (état 0x4 = Ctrl, 0x1 = Maj) : sélection, ou début d'un glisser."""
        i = self._case(x, y)
        self._geste = None
        if i is None:
            return
        ctrl, maj = bool(etat & 0x4), bool(etat & 0x1)
        if ctrl or maj or i not in self.selection:
            self.cliquer(i, ctrl=ctrl, maj=maj)
            self._geste = (x, y, i, False, False)
        else:
            # Page déjà choisie : un clic la choisit seule, un glisser déplace tout le groupe.
            self._geste = (x, y, i, True, False)

    def _glisse_toile(self, x: float, y: float):
        if not self._geste or self.edition is None or self._geste[2] not in self.selection:
            return
        x0, y0, i, differe, glisse = self._geste
        if not glisse and abs(x - x0) + abs(y - y0) < 8 * self.echelle:
            return
        self._geste = (x0, y0, i, differe, True)
        self.toile.delete("insertion")
        place = self._place(x, y)
        n = len(self.vignettes)
        ax0, ay0, ax1, ay1 = self._position(min(place, n - 1))
        trait_x = ax1 if place == n else ax0
        self.toile.create_line(trait_x, ay0 + 6, trait_x, ay1 - 6, fill=theme.ACCENT,
                               width=round(4 * self.echelle), tags="insertion")
        self.toile.configure(cursor="fleur")

    def _relache_toile(self, x: float, y: float):
        geste, self._geste = self._geste, None
        self.toile.delete("insertion")
        self.toile.configure(cursor="")
        if not geste:
            return
        _x0, _y0, i, differe, glisse = geste
        if not glisse:
            if differe:
                self.cliquer(i)
            return
        pages, place = sorted(self.selection), self._place(x, y)
        nouvelles = self.retoucher(lambda: self.edition.placer_pages(pages, place))
        if nouvelles:
            self.selection = set(nouvelles)
            self._ancre = None
            self._marquer_selection()
            self.apercu.aller(nouvelles[0])
            self.app.statut(f"{len(pages)} page(s) déplacée(s). Ctrl+Z pour annuler.")

    def cliquer(self, index: int, ctrl: bool = False, maj: bool = False):
        """Sélection à la souris : simple, ajout (Ctrl) ou suite (Maj)."""
        if maj and self._ancre is not None:
            debut, fin = sorted((self._ancre, index))
            self.selection = set(range(debut, fin + 1))
        elif ctrl:
            self.selection ^= {index}
            self._ancre = index
        else:
            self.selection = {index}
            self._ancre = index
        self._marquer_selection()
        self.apercu.aller(index)

    def _choisies(self) -> list[int] | None:
        if self.edition is None:
            self.app.exiger_pdf()
            return None
        if not self.selection:
            self.app.statut("Sélectionnez d'abord une ou plusieurs pages.")
            return None
        return sorted(self.selection)

    # ------------------------------------------------------- actions

    def tourner(self, angle: int):
        pages = self._choisies()
        self._a_actualiser = pages
        pivotees = pages and self.retoucher(lambda: self.edition.pivoter(pages, angle))
        self._a_actualiser = None
        if pivotees:
            self.app.statut(f"{len(pages)} page(s) pivotée(s).")

    def supprimer(self):
        pages = self._choisies()
        if pages and self.retoucher(lambda: self.edition.supprimer(pages)):
            # On garde une page sélectionnée là où étaient les pages supprimées.
            reste = self.edition.doc.page_count
            self.selection = {min(pages[0], reste - 1)}
            self._ancre = None
            self._marquer_selection()
            self.app.statut(f"{len(pages)} page(s) supprimée(s).")

    def deplacer(self, sens: int):
        pages = self._choisies()
        if not pages:
            return
        nouvelles = self.retoucher(lambda: self.edition.deplacer_pages(pages, sens))
        if nouvelles:
            self.selection = set(nouvelles)
            self._ancre = None
            self._marquer_selection()
            self.apercu.aller(nouvelles[0])

    def inserer(self):
        if self.edition is None:
            return self.app.exiger_pdf()
        chemin = filedialog.askopenfilename(initialdir=self.app.dernier_dossier, filetypes=[("PDF", "*.pdf")])
        if not chemin:
            return
        apres = max(self.selection) if self.selection else None
        while True:
            try:
                nouvelles = self.edition.inserer(chemin, apres)
                break
            except core.PasswordRequired:
                if not self.app.demander_mot_de_passe(chemin):
                    return self.app.statut("Insertion annulée : mot de passe requis.")
            except Exception:  # noqa: BLE001 - PDF illisible : la copie de travail est intacte
                logging.exception("Insertion impossible : %s", chemin)
                return messagebox.showerror(identite.NOM_AFFICHE, f"Impossible d'insérer « {os.path.basename(chemin)} ».")
        self.apercu.remplacer(self.edition.doc)
        self.selection = set(nouvelles)
        self._ancre = None
        self.maj_etat()
        self.apercu.aller(nouvelles[0])
        self.app.statut(f"{len(nouvelles)} page(s) insérée(s) depuis « {os.path.basename(chemin)} ».")

    def extraire(self):
        """Enregistre les pages sélectionnées dans un nouveau PDF ; le document ne change pas."""
        pages = self._choisies()
        if not pages:
            return
        base = os.path.splitext(os.path.basename(self.app.chemin_pdf or "document"))[0]
        sortie = filedialog.asksaveasfilename(
            initialdir=self.app.dernier_dossier, initialfile=f"{base}_pages_{_plages(pages)}.pdf",
            defaultextension=".pdf", filetypes=[("PDF", "*.pdf")])
        if not sortie:
            return
        donnees = self.edition.doc.tobytes()  # lu ici : le traitement ne touche pas au document affiché

        def travail(_p):
            doc = fitz.open("pdf", donnees)
            try:
                return core.extract_pages(doc, pages, sortie)
            finally:
                doc.close()

        self.app.statut("Extraction…")
        self.app.lancer(travail, fin=lambda n: self.app.proposer_ouverture(sortie, f"{n} page(s) extraite(s)."))
