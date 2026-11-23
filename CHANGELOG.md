# Nouveautés de Pagilis PDF (anciennement PDF Studio)

Chaque version publiée a sa section ci-dessous : la CI refuse de publier une
version qui n'en a pas, et son contenu s'affiche dans la fenêtre de mise à jour.

Numérotation : MAJEUR.MINEUR.CORRECTIF.
- CORRECTIF (1.1.1) : corrections uniquement ;
- MINEUR (1.2.0) : nouveautés compatibles ;
- MAJEUR (2.0.0) : changements qui cassent des habitudes.

Une bêta (tag `v1.2.0-beta.1`) reprend les notes de sa version (`1.2.0`) si
elle n'a pas de section propre.

## [1.12.0]

- Nouvelle signature : un cadre beaucoup plus grand pour signer à l'aise.
- « Annuler le trait » (ou Ctrl+Z) retire seulement le dernier trait ; « Effacer » recommence tout.
- Épaisseur du trait au choix : Fin, Moyen ou Épais.
- Vos signatures ont un nom (Signature, Initiales, Paraphe…), affiché sous chacune. Renommez-les ou supprimez-les dans « Mes signatures ».

## [1.11.0]

- Mes signatures : gardez plusieurs signatures (signature complète, initiales, tampon). Créez-les, importez une image ou supprimez-les depuis l'accueil, même sans PDF ouvert.
- Cases de signature : tracez une case « Signer ici : Client » pour la personne qui recevra le PDF. Elle est reconnue par Adobe, Aperçu et les navigateurs. Cliquez dans une case pour la signer : la signature s'y ajuste.
- Signer fonctionne maintenant comme Modifier et Pages : chaque signature posée s'annule (Ctrl+Z), et « Enregistrer » garde le tout en une fois.

## [1.10.0]

- Modifier : ajoutez du texte n'importe où sur la page (date, mention, champ de formulaire), en noir ou en bleu, en 4 tailles. Ctrl+Z l'annule.
- Pages : faites glisser les vignettes pour changer l'ordre des pages. Une barre bleue montre où elles iront.
- Recherche : une espace insécable est traitée comme une espace ordinaire. Une recherche de plusieurs mots trouve maintenant les lignes corrigées, et le texte des PDF qui mettent une espace insécable avant « : ».

## [1.9.0]

- Signature tracée : le trait imite un stylo plume, avec des pleins épais, des déliés fins, des bords lisses et un tracé adouci.

## [1.8.0]

- Signer : créez votre signature dans le logiciel, en la traçant à la souris, au doigt ou au stylet, ou en écrivant votre nom dans une police manuscrite. Encre noire ou bleue.
- La signature créée est gardée sur ce PC et reprise automatiquement à chaque signature.

## [1.7.2]

- Plus rapide sur les longs documents : « Pages » s'affiche et pivote en une
  fraction de seconde (plusieurs secondes avant sur 300 pages) ; rechercher et
  remplacer ne lisent en détail que les pages qui contiennent le mot.
- « Remplacer partout » tourne en arrière-plan avec une barre de progression :
  la fenêtre ne se fige plus.
- Plus fiable : une erreur imprévue est signalée et notée dans le journal ; le
  journal ne grossit plus sans fin ; la mémoire réservée aux annulations est
  plafonnée.
- Corrections : la pastille « Non enregistré » disparaît quand on annule
  jusqu'au document enregistré ; un bloc ramené à sa place n'est plus réécrit ;
  Ctrl+S fonctionne pendant une correction ; « Ouvrir avec Pagilis PDF »
  accepte les documents Word ; les messages d'erreur de Word gardent leurs
  accents.

## [1.7.1]

- Le logiciel s'appelle désormais Pagilis PDF : fenêtre, raccourcis, « Applications
  installées » et « Ouvrir avec ». Une installation existante est renommée
  automatiquement à la mise à jour, en gardant ses raccourcis.

## [1.7.0]

- Nouveau logo : une page bleue au coin plié, avec « Pdf ». Il apparaît dans
  la barre des tâches, sur l'exe, les raccourcis, la barre du haut et l'accueil.

## [1.6.0]

- Nouvelle présentation, plus claire et plus professionnelle : police et icônes
  de Windows, une seule couleur pour l'action principale, et le document au
  centre de la fenêtre.
- Barre du haut : le document ouvert, une pastille « Non enregistré » et les
  boutons Ouvrir, Annuler et Enregistrer (Ctrl+O, Ctrl+Z, Ctrl+S).
- Fonctions regroupées : Éditer (Modifier le texte, Pages, Signer), Assembler
  (Fusionner, Découper), Outils (Reconnaître le texte).
- « Remplacer du texte » et « Déplacer du texte » rejoignent « Modifier le
  texte » : rechercher et remplacer dans tout le document, annulable.
- Écran d'accueil avec les fichiers récents ; numéro de page à taper sous
  l'aperçu ; bulles d'aide sur les boutons à icône.
- Réglages en mots simples (langue « Français », précision « Standard »…) ;
  les réglages techniques sont rangés dans « Options avancées ».
- Plus de fenêtre « Ouvrir le résultat ? » après chaque traitement : la barre
  d'état propose « Ouvrir » ou « Continuer dessus ».

## [1.5.0]

- Nouvelle fonction « Pages » : les vignettes de toutes les pages, pour
  pivoter, supprimer, monter ou descendre des pages, insérer un autre PDF et
  extraire une sélection dans un nouveau fichier. Sélection au clic,
  Ctrl+clic et Maj+clic.
- « Modifier » et « Pages » travaillent sur le même document : les retouches
  de texte et de pages s'annulent (Ctrl+Z) et s'enregistrent (Ctrl+S)
  ensemble.

## [1.4.0]

- Une ligne corrigée garde sa police d'origine : celle du PDF quand elle
  contient tous les caractères du nouveau texte, sinon la même police
  installée sur le PC (Aptos, Calibri, Garamond…). Arial, Times ou Courier ne
  servent plus qu'en dernier recours, et la barre d'état indique la police
  utilisée. Vaut aussi pour « Remplacer du texte » et « Déplacer du texte ».
- Un texte plus long que l'original est réduit selon la largeur réelle de sa
  police.

## [1.3.0]

- Nouvelle fonction « Modifier » : cliquez sur une ligne pour corriger son
  texte sur place, faites glisser un bloc pour le déplacer. Les retouches
  s'annulent (Ctrl+Z) et s'enregistrent en une fois (Ctrl+S).
- Les documents Word (.docx, .doc, .rtf, .odt) s'ouvrent : ils sont convertis
  en PDF avec Microsoft Word, ou LibreOffice s'il n'y a pas Word.
- Pagilis prévient avant de fermer ou d'ouvrir un autre fichier si des
  retouches ne sont pas enregistrées.
- Apparence sombre pour tous ; le choix « Apparence » est retiré de la barre
  latérale.

## [1.2.0]

- PDF Studio s'appelle désormais Pagilis.
- Si PDF Studio est installé sur votre PC, il devient automatiquement Pagilis :
  nouveaux raccourcis et nouvelle entrée dans « Applications installées »,
  l'ancienne installation est retirée. Vos préférences sont conservées.
- L'option « Versions bêta » est retirée : seules les versions publiées pour
  tous sont proposées.
- Le bouton « Aide et limites » est retiré de la barre latérale.

## [1.1.0]

- Versions bêta : activez « Versions bêta » dans la barre latérale pour recevoir
  les versions de test avant tout le monde.
- La fenêtre de mise à jour affiche les nouveautés de la version proposée.
- Plus sûr : une nouvelle version est d'abord vérifiée sur votre PC ; si elle
  ne fonctionne pas, votre version actuelle est conservée. La version remplacée
  est gardée en sauvegarde.
- Exe plus léger (environ 10 Mo de moins).
- Corrections : l'aperçu ne laisse plus de rendu en attente à la fermeture ;
  la vérification des mises à jour pouvait ignorer la configuration.

## [1.0.2]

- Aperçu de page avec navigation et zoom ; signature posée et déplacée à la
  souris ; blocs de texte sélectionnés et déplacés à la souris ; occurrences
  surlignées ; mots reconnus par l'OCR encadrés.
- Les PDF protégés demandent leur mot de passe.
- Application portable, installable sur le PC sans droits administrateur.
- Fusion : la liste se réordonne fichier par fichier.
- Corrections : gras et police serif conservés au remplacement ; messages
  d'erreur qui faisaient planter l'application ; scripts de mise à jour.

## [1.0.1]

- Fonctions dans une barre latérale à gauche, thème clair ou sombre.
- Mises à jour proposées au démarrage, avec « Ne plus me proposer cette version ».

## [1.0.0]

- Première version : fusion, division, signature/tampon, remplacement et
  déplacement de texte, OCR des PDF scannés.
