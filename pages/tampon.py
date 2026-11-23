# SPDX-License-Identifier: AGPL-3.0-or-later
"""Signature / Tampon : poser une image sur les pages, à la souris, et cases à signer.

Comme « Modifier » et « Pages », Signer retouche la copie de travail : chaque geste
s'annule (Ctrl+Z) et le tout s'enregistre en une fois (Ctrl+S).
"""

from __future__ import annotations

import io
import os
from tkinter import filedialog, messagebox, simpledialog

import customtkinter as ctk
import pymupdf as fitz
from PIL import Image, ImageTk

import cases
import identite
import pdf_core as core
import signature
import theme
from pages.copie import PageCopie


class PageTampon(PageCopie):
    nom = "Signature / Tampon"
    libelle = "Signer"
    groupe = "Éditer"
    icone = "signer"
    description = "Poser une signature ou un tampon, préparer et signer des cases « Signer ici »."

    def construire(self):
        self.image_tampon: str | None = None
        self.ratio_tampon = 0.5
        self._cache_tampon = (None, None)  # (clé, PhotoImage) de l'image en surimpression
        self._geste = None  # décalage du clic dans l'image pendant un glisser
        self.tracer = False  # mode « tracer une case à signer »
        self._cadre_case = None  # (début, fin) du cadre en cours de tracé
        self.section("Signature")
        ligne = self.ligne()
        theme.bouton(ligne, "Mes signatures…", self.mes_signatures, icone_nom="signer").pack(
            side="left", fill="x", expand=True, padx=(0, 6))
        theme.bouton(ligne, "Nouvelle…", self.creer_signature, icone_nom="ajouter").pack(side="left")
        ligne = self.ligne()
        theme.bouton(ligne, "Autre image (PNG, JPG)…", self.choisir_tampon, style="discret",
                     icone_nom="ouvrir").pack(side="left")
        self.label_tampon = ctk.CTkLabel(self.cadre, text="Créez votre signature, ou choisissez une image "
                                         "(PNG ou JPG : signature scannée, tampon…).",
                                         anchor="w", font=theme.police(12), text_color=theme.TEXTE_2,
                                         wraplength=260, justify="left")
        self.label_tampon.pack(fill="x", padx=16, pady=(2, 2))
        self.blanc_transparent = ctk.BooleanVar(value=True)
        self.case(self.cadre, "Rendre le fond blanc transparent", self.blanc_transparent,
                  commande=self.apercu.redessiner)

        self.section("Placement")
        self.aide("Cliquez sur la page pour poser l'image, puis faites-la glisser.")
        self.largeur = ctk.DoubleVar(value=150)
        self.curseur("Taille", 40, 500, self.largeur, commande=self.apercu.redessiner)
        self.opacite = ctk.DoubleVar(value=100)
        self.curseur("Opacité", 10, 100, self.opacite, suffixe=" %")
        self.rotation_tampon = ctk.StringVar(value="0°")
        self.segments("Rotation", ["0°", "90°", "180°", "270°"], self.rotation_tampon,
                      commande=lambda _v: self.apercu.redessiner())

        self.section("Cases à signer")
        self.aide("Préparez une case « Signer ici » pour vous ou pour la personne qui recevra le PDF : "
                  "tracez-la sur la page. Cliquez ensuite dans une case pour la signer.")
        self.bouton_tracer = theme.bouton(self.ligne(), "Tracer une case", self.basculer_tracer,
                                          icone_nom="ajouter")
        self.bouton_tracer.pack(fill="x")
        self.label_cases = ctk.CTkLabel(self.cadre, text="", anchor="w", font=theme.police(12),
                                        text_color=theme.TEXTE_2)
        self.label_cases.pack(fill="x", padx=16, pady=(2, 0))

        self.section("Pages")
        self.portee_tampon = ctk.StringVar(value="page")
        for texte, valeur in (("Page affichée", "page"), ("Toutes les pages", "toutes"),
                              ("Ces pages :", "plage")):
            self.radio(self.cadre, texte, self.portee_tampon, valeur)
        self.entree_plage_tampon = self.champ("", "")
        self.entree_plage_tampon.configure(placeholder_text="1, 3-5")

        avance = self.options_avancees()
        self.aide("Position exacte du coin haut gauche, en points (1/72 de pouce).", parent=avance)
        self.entree_x = self.champ("Gauche", "72", parent=avance)
        self.entree_y = self.champ("Haut", "72", parent=avance)
        for entree in (self.entree_x, self.entree_y):
            entree.bind("<KeyRelease>", lambda _e: self.apercu.redessiner())
        self.bouton_action("Poser la signature", self.lancer_tampon, icone_nom="signer")
        memorisee = signature.signature_enregistree(self.app.dossier_donnees)
        if memorisee:
            self.utiliser_image(memorisee, "Ma signature")

    def _rotation(self) -> int:
        return int(self.rotation_tampon.get().rstrip("°"))

    def choisir_tampon(self):
        chemin = filedialog.askopenfilename(initialdir=self.app.dernier_dossier,
                                            filetypes=[("Images", "*.png *.jpg *.jpeg")])
        if not chemin:
            return
        self.utiliser_image(chemin, os.path.basename(chemin))

    def creer_signature(self, ensuite=None):
        """Fenêtre « Créer ma signature » ; la signature créée devient l'image à poser."""
        def fin(chemin):
            self.utiliser_image(chemin, "Ma signature")
            if ensuite:
                ensuite(chemin)
        signature.FenetreSignature(self.app, self.app.dossier_donnees, fin)

    def mes_signatures(self, ensuite=None, titre: str = "Choisir une signature"):
        """Bibliothèque des signatures : celle qu'on clique devient l'image à poser."""
        def fin(chemin):
            self.utiliser_image(chemin, "Ma signature")
            if ensuite:
                ensuite(chemin)
        signature.FenetreMesSignatures(self.app, self.app.dossier_donnees, choisir=fin, titre=titre)

    def utiliser_image(self, chemin: str, libelle: str):
        try:
            with Image.open(chemin) as im:
                self.ratio_tampon = im.height / im.width
        except OSError:
            return messagebox.showerror(identite.NOM_AFFICHE, "Image illisible.")
        self.image_tampon = chemin
        self._cache_tampon = (None, None)  # une signature recréée garde le même chemin
        self.label_tampon.configure(text=libelle, text_color=theme.TEXTE)
        self.apercu.redessiner()

    def _rect_tampon(self) -> fitz.Rect | None:
        """Rectangle du tampon dans le repère affiché, d'après les champs."""
        try:
            x, y = (float(e.get()) for e in (self.entree_x, self.entree_y))
            largeur = float(self.largeur.get())
        except ValueError:
            return None
        if self._rotation() in (90, 270):
            return fitz.Rect(x, y, x + largeur * self.ratio_tampon, y + largeur)
        return fitz.Rect(x, y, x + largeur, y + largeur * self.ratio_tampon)

    # ------------------------------------------------------- aperçu

    def dessiner(self, apercu):
        if self._cadre_case:
            a, b = self._cadre_case
            apercu.canevas.create_rectangle(*apercu.rect_canevas(fitz.Rect(a, b)), outline=theme.ACCENT,
                                            width=2, dash=(6, 3), tags="superposition")
        if self.tracer:
            return
        rect = self._rect_tampon()
        if not self.image_tampon or rect is None:
            return
        x0, y0, x1, y1 = apercu.rect_canevas(rect)
        taille = (max(1, int(x1 - x0)), max(1, int(y1 - y0)))
        cle = (self.image_tampon, taille, self._rotation(), self.blanc_transparent.get())
        if self._cache_tampon[0] != cle:
            image = Image.open(self.image_tampon).convert("RGBA")
            if self.blanc_transparent.get():
                image = Image.open(io.BytesIO(core._prepare_stamp_image(
                    self.image_tampon, core.StampOptions(0, 0, 1, white_transparent=True))))
            image = image.rotate(-self._rotation(), expand=True).resize(taille)
            self._cache_tampon = (cle, ImageTk.PhotoImage(image))
        apercu.canevas.create_image(x0, y0, image=self._cache_tampon[1], anchor="nw", tags="superposition")
        apercu.canevas.create_rectangle(x0, y0, x1, y1, outline="#1F6AA5", width=2, dash=(4, 2),
                                        tags="superposition")

    def appui(self, point):
        self._geste = None
        if self.tracer:
            self._cadre_case = (point, point)
            return
        page = self.apercu.page
        case = cases.case_en(page, point) if page is not None else None
        if case is not None:
            return self.signer_case(case)
        if not self.image_tampon:
            return self.app.statut("Choisissez d'abord une image.")
        rect = self._rect_tampon()
        if rect is None or not rect.contains(point):
            # Clic hors de l'image : on la centre sur le clic.
            largeur = float(self.largeur.get())
            rect = self._rect_tampon() or fitz.Rect(0, 0, largeur, largeur * self.ratio_tampon)
            self.remplir(self.entree_x, max(0, point.x - rect.width / 2))
            self.remplir(self.entree_y, max(0, point.y - rect.height / 2))
            rect = self._rect_tampon()
        self._geste = point - rect.tl
        self.apercu.redessiner()

    def glisse(self, point):
        if self._cadre_case:
            self._cadre_case = (self._cadre_case[0], point)
            return self.apercu.redessiner()
        if self._geste is None:
            return
        self.remplir(self.entree_x, max(0, point.x - self._geste.x))
        self.remplir(self.entree_y, max(0, point.y - self._geste.y))
        self.apercu.redessiner()

    def relache(self, point):
        self._geste = None
        if self._cadre_case:
            debut, self._cadre_case = self._cadre_case[0], None
            self.apercu.redessiner()
            rect = fitz.Rect(debut, point)
            rect.normalize()
            if rect.width < cases.LARGEUR_MIN or rect.height < cases.HAUTEUR_MIN:
                return self.app.statut("Case trop petite : faites glisser la souris pour la tracer.")
            nom = simpledialog.askstring(identite.NOM_AFFICHE, "Qui signe ici ?", initialvalue="Client",
                                         parent=self.app)
            if nom is None:
                return
            index = self.apercu.index
            if self.retoucher(lambda: self.edition.creer_case(index, rect, nom)):
                self.app.statut(f"Case « {nom.strip() or 'Signature'} » ajoutée. Enregistrez pour la garder.")

    # ------------------------------------------------------- cases à signer

    def basculer_tracer(self):
        if not self.tracer and not self.app.exiger_pdf():
            return
        self.tracer = not self.tracer
        self._cadre_case = None
        self.bouton_tracer.configure(text="Arrêter de tracer" if self.tracer else "Tracer une case")
        if self.tracer:
            self.app.statut("Faites glisser la souris sur la page pour tracer la case.")
        self.apercu.redessiner()

    def interrompre(self):
        if self.tracer:
            self.basculer_tracer()

    def signer_case(self, case):
        """Clic dans une case vide : on choisit la signature, elle remplit la case."""
        index = self.apercu.index

        def poser(chemin):
            if self.edition is None or self.apercu.index != index:
                return
            if self.retoucher(lambda: self.edition.signer_case(index, case.xref, chemin)):
                self.app.statut(f"Case « {case.nom} » signée. Enregistrez pour garder la signature.")

        gardees = signature.liste(self.app.dossier_donnees)
        if not gardees:
            return self.creer_signature(ensuite=poser)
        if len(gardees) == 1:
            return poser(gardees[0])
        self.mes_signatures(ensuite=poser, titre=f"Signer « {case.nom} »")

    def edition_changee(self):
        n = cases.nombre_vides(self.edition.doc) if self.edition and not self.app.travaux_en_cours else 0
        self.label_cases.configure(text=f"{n} case(s) à signer dans ce document." if n else "")

    # ------------------------------------------------------- traitement

    def lancer_tampon(self):
        if not self.app.exiger_pdf() or self.edition is None:
            return
        if not self.image_tampon:
            return messagebox.showinfo(identite.NOM_AFFICHE, "Choisissez d'abord une image.")
        try:
            opts = core.StampOptions(
                x=float(self.entree_x.get()),
                y=float(self.entree_y.get()),
                width=float(self.largeur.get()),
                opacity=float(self.opacite.get()) / 100,
                rotation=self._rotation(),
                white_transparent=self.blanc_transparent.get(),
            )
        except ValueError:
            return messagebox.showwarning(identite.NOM_AFFICHE, "Vérifiez les valeurs numériques.")
        portee = self.portee_tampon.get()
        plage = self.entree_plage_tampon.get().strip()
        if portee == "plage" and not plage:
            return messagebox.showwarning(identite.NOM_AFFICHE, "Indiquez la plage de pages.")
        nombre = self.edition.doc.page_count
        try:
            if portee == "plage":
                pages = core.parse_page_ranges(plage, nombre)
            elif portee == "toutes":
                pages = list(range(nombre))
            else:
                pages = [self.apercu.index]
        except core.PagilisError as exc:
            return messagebox.showwarning(identite.NOM_AFFICHE, str(exc))
        image = self.image_tampon
        n = self.retoucher(lambda: self.edition.poser(image, pages, opts))
        if n:
            self.app.statut(f"Signature posée sur {n} page(s). Enregistrez pour la garder (Ctrl+S).")
