import json
import os

catalog_path = "android/app/src/main/canonicalAssets/ai/psychometrics/catalog.json"
with open(catalog_path, "r", encoding="utf-8") as f:
    catalog = json.load(f)

print(f"Loaded {len(catalog)} instruments from catalog.json")

# 1. BDI Form
bdi_form = {
    "version": "BDI-II-21-ID-2026",
    "title": "Beck Depression Inventory (BDI-II)",
    "instructions": "Pilihlah satu pernyataan pada setiap butir yang paling menggambarkan perasaan Anda selama dua minggu terakhir termasuk hari ini.",
    "sourceTitle": "Beck Depression Inventory-II (BDI-II) · Beck, Steer, & Brown (1996); Validasi Indonesia: Ginting et al. (2013)",
    "sourceUrl": "https://www.pearsonassessments.com/store/usassessments/en/Store/Professional-Assessments/Personality-%26-Biopsychosocial/Beck-Depression-Inventory/p/100000159",
    "sourceLocator": "PsikoTest/BDI/BDI_Acuan_dan_Skoring.md · §2–5",
    "choices": [
        "0 - Tidak mengalami sama sekali",
        "1 - Ringan / kadang-kadang",
        "2 - Sedang / sering",
        "3 - Berat / parah / terus-menerus"
    ],
    "values": [0, 1, 2, 3],
    "scales": [
        {
            "id": "cognitive",
            "label": "Subskala Kognitif-Afektif",
            "meaning": "Menggambarkan persepsi diri, pesimisme, rasa bersalah, dan emosi kesedihan (Butir 1–14)."
        },
        {
            "id": "somatic",
            "label": "Subskala Somatik",
            "meaning": "Menggambarkan keluhan energi, pola tidur, nafsu makan, dan kelelahan fisik (Butir 15–21)."
        }
    ],
    "items": [
        {
            "text": "1. Kesedihan: Seberapa sering Anda merasa sedih atau murung akhir-akhir ini?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak merasa sedih.",
                "Saya merasa sedih sebagian besar waktu.",
                "Saya merasa sedih sepanjang waktu.",
                "Saya sangat sedih atau tidak bahagia sampai tidak tahan lagi."
            ]
        },
        {
            "text": "2. Pesimisme: Bagaimana pandangan Anda terhadap masa depan Anda?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak merasa putus asa tentang masa depan saya.",
                "Saya merasa lebih putus asa tentang masa depan saya dibanding biasanya.",
                "Saya tidak mengharapkan hal-hal akan membaik bagi saya.",
                "Saya merasa masa depan saya tanpa harapan dan hanya akan bertambah buruk."
            ]
        },
        {
            "text": "3. Kegagalan Masa Lalu: Bagaimana perasaan Anda mengenai pencapaian hidup Anda?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak merasa seperti orang gagal.",
                "Saya telah gagal lebih sering dari yang seharusnya.",
                "Saat mengingat masa lalu, saya melihat banyak kegagalan.",
                "Saya merasa saya benar-benar gagal sebagai pribadi."
            ]
        },
        {
            "text": "4. Kehilangan Kesenangan: Seberapa besar Anda dapat menikmati hal-hal yang biasanya Anda sukai?",
            "scale": "cognitive",
            "choices": [
                "Saya menikmati hal-hal seperti biasanya.",
                "Saya tidak menikmati hal-hal sebanyak yang biasanya.",
                "Saya hanya mendapat sangat sedikit kenikmatan dari hal-hal yang dulu saya nikmati.",
                "Saya tidak dapat menikmati apa pun lagi."
            ]
        },
        {
            "text": "5. Perasaan Bersalah: Seberapa sering Anda merasa bersalah atas suatu tindakan?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak merasa sangat bersalah.",
                "Saya merasa bersalah atas banyak hal yang saya lakukan atau seharusnya saya lakukan.",
                "Saya merasa cukup bersalah sebagian besar waktu.",
                "Saya merasa bersalah sepanjang waktu."
            ]
        },
        {
            "text": "6. Perasaan Dihukum: Apakah Anda merasa seolah-olah Anda sedang dihukum atas suatu hal?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak merasa sedang dihukum.",
                "Saya merasa mungkin sedang dihukum.",
                "Saya mengharapkan akan dihukum.",
                "Saya merasa saya sedang dihukum."
            ]
        },
        {
            "text": "7. Ketidaksukaan pada Diri Sendiri: Bagaimana sikap atau penerimaan Anda terhadap diri sendiri?",
            "scale": "cognitive",
            "choices": [
                "Perasaan saya tentang diri saya sama seperti biasanya.",
                "Saya kehilangan rasa percaya diri.",
                "Saya kecewa terhadap diri saya sendiri.",
                "Saya membenci diri saya sendiri."
            ]
        },
        {
            "text": "8. Kritik terhadap Diri Sendiri: Seberapa sering Anda menyalahkan atau mengkritik kekurangan diri?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak mengkritik atau menyalahkan diri saya lebih dari biasanya.",
                "Saya lebih kritis terhadap diri saya dibanding biasanya.",
                "Saya mengkritik diri saya atas semua kesalahan saya.",
                "Saya menyalahkan diri saya atas semua hal buruk yang terjadi."
            ]
        },
        {
            "text": "9. Pikiran atau Keinginan Mengakhiri Hidup (Item Kritis Keamanan):",
            "scale": "cognitive",
            "choices": [
                "Saya tidak memiliki pikiran untuk mengakhiri hidup.",
                "Saya memiliki pikiran untuk mengakhiri hidup tetapi tidak akan melakukannya.",
                "Saya ingin mengakhiri hidup saya.",
                "Saya akan mengakhiri hidup saya jika memiliki kesempatan."
            ]
        },
        {
            "text": "10. Menangis: Seberapa sering Anda menangis atau merasa ingin menangis?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak menangis lebih sering dari biasanya.",
                "Saya lebih sering menangis sekarang dibanding biasanya.",
                "Saya menangis atas setiap hal kecil.",
                "Saya merasa ingin menangis tetapi tidak dapat meneteskan air mata."
            ]
        },
        {
            "text": "11. Kegelisahan: Seberapa sering Anda merasa gelisah atau sulit untuk tenang?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak merasa lebih gelisah dibanding biasanya.",
                "Saya merasa lebih gelisah dari biasanya.",
                "Saya sangat gelisah sehingga sulit diam.",
                "Saya sangat gelisah hingga harus terus bergerak."
            ]
        },
        {
            "text": "12. Kehilangan Minat: Bagaimana minat Anda terhadap orang lain atau kegiatan di sekitar Anda?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak kehilangan minat pada orang lain atau aktivitas.",
                "Saya kurang berminat pada orang lain atau aktivitas dibanding sebelumnya.",
                "Saya kehilangan sebagian besar minat pada orang lain atau hal-hal lain.",
                "Sangat sulit untuk tertarik pada apa pun."
            ]
        },
        {
            "text": "13. Keraguan Mengambil Keputusan: Seberapa mudah Anda mengambil keputusan sehari-hari?",
            "scale": "cognitive",
            "choices": [
                "Saya dapat mengambil keputusan sebaik biasanya.",
                "Saya merasa lebih sulit mengambil keputusan dibanding biasanya.",
                "Saya mengalami kesulitan besar dalam mengambil keputusan.",
                "Saya kesulitan mengambil keputusan apa pun."
            ]
        },
        {
            "text": "14. Rasa Tidak Berharga: Bagaimana Anda menilai keberhargaan diri Anda?",
            "scale": "cognitive",
            "choices": [
                "Saya tidak merasa diri saya tidak berharga.",
                "Saya tidak menganggap diri saya berharga dan berguna seperti sebelumnya.",
                "Saya merasa lebih tidak berharga dibanding orang lain.",
                "Saya merasa sama sekali tidak berharga."
            ]
        },
        {
            "text": "15. Kehilangan Energi: Bagaimana tingkat tenaga dan energi fisik Anda?",
            "scale": "somatic",
            "choices": [
                "Saya memiliki energi sebanyak biasanya.",
                "Saya memiliki lebih sedikit energi dibanding sebelumnya.",
                "Saya tidak memiliki cukup energi untuk melakukan banyak hal.",
                "Saya tidak memiliki energi untuk melakukan apa pun."
            ]
        },
        {
            "text": "16. Perubahan Pola Tidur: Bagaimana kualitas dan durasi tidur Anda?",
            "scale": "somatic",
            "choices": [
                "Pola tidur saya tidak mengalami perubahan.",
                "Saya tidur sedikit lebih banyak atau sedikit lebih sedikit dari biasanya.",
                "Saya tidur jauh lebih banyak atau jauh lebih sedikit dari biasanya.",
                "Saya tidur hampir sepanjang hari atau terbangun 1-2 jam lebih awal dan tidak dapat tidur lagi."
            ]
        },
        {
            "text": "17. Mudah Tersinggung: Seberapa mudah Anda merasa kesal atau tersinggung oleh hal-hal kecil?",
            "scale": "somatic",
            "choices": [
                "Saya tidak lebih mudah tersinggung dibanding biasanya.",
                "Saya lebih mudah tersinggung dibanding biasanya.",
                "Saya jauh lebih mudah tersinggung dibanding biasanya.",
                "Saya mudah tersinggung sepanjang waktu."
            ]
        },
        {
            "text": "18. Perubahan Nafsu Makan: Bagaimana nafsu makan Anda selama dua minggu ini?",
            "scale": "somatic",
            "choices": [
                "Nafsu makan saya tidak mengalami perubahan.",
                "Nafsu makan saya sedikit lebih berkurang atau bertambah dari biasanya.",
                "Nafsu makan saya jauh lebih berkurang atau bertambah dari biasanya.",
                "Saya tidak memiliki nafsu makan sama sekali atau selalu ingin makan sepanjang waktu."
            ]
        },
        {
            "text": "19. Kesulitan Konsentrasi: Seberapa baik Anda dapat memusatkan perhatian pada tugas/bacaan?",
            "scale": "somatic",
            "choices": [
                "Saya dapat berkonsentrasi sebaik biasanya.",
                "Saya tidak dapat berkonsentrasi sebaik biasanya.",
                "Sangat sulit untuk tetap fokus pada apa pun dalam waktu lama.",
                "Saya merasa tidak dapat berkonsentrasi pada apa pun."
            ]
        },
        {
            "text": "20. Kelelahan: Seberapa cepat Anda merasa lelah ketika beraktivitas?",
            "scale": "somatic",
            "choices": [
                "Saya tidak lebih lelah dibanding biasanya.",
                "Saya lebih cepat lelah dibanding biasanya.",
                "Saya terlalu lelah untuk melakukan banyak hal yang biasa saya lakukan.",
                "Saya terlalu lelah untuk melakukan hampir semua hal."
            ]
        },
        {
            "text": "21. Kehilangan Minat Seksual: Bagaimana minat Anda terhadap hubungan intim/seksual?",
            "scale": "somatic",
            "choices": [
                "Saya tidak melihat adanya perubahan minat seksual akhir-akhir ini.",
                "Saya kurang tertarik pada seks dibanding sebelumnya.",
                "Saya jauh lebih kurang tertarik pada seks sekarang.",
                "Saya telah kehilangan minat pada seks sepenuhnya."
            ]
        }
    ],
    "average": False,
    "minimumAnswers": 21,
    "interpretationNote": "Rentang skor BDI-II: 0–13 Minimal, 14–19 Ringan, 20–28 Sedang, 29–63 Berat. Nilai >= 1 pada Butir 9 memerlukan asesmen keselamatan segera."
}

# 2. SRQ-29 Form
srq_questions = [
    "Apakah Anda sering merasa sakit kepala?",
    "Apakah Anda kehilangan nafsu makan?",
    "Apakah tidur Anda tidak nyenyak?",
    "Apakah Anda mudah merasa takut?",
    "Apakah Anda merasa cemas, tegang, atau khawatir?",
    "Apakah tangan Anda gemetar?",
    "Apakah Anda mengalami gangguan pencernaan?",
    "Apakah Anda merasa sulit berpikir jernih?",
    "Apakah Anda merasa tidak bahagia?",
    "Apakah Anda lebih sering menangis?",
    "Apakah Anda merasa sulit untuk menikmati aktivitas sehari-hari?",
    "Apakah Anda mengalami kesulitan untuk mengambil keputusan?",
    "Apakah aktivitas/tugas sehari-hari Anda terbengkalai?",
    "Apakah Anda merasa tidak mampu berperan dalam kehidupan ini?",
    "Apakah Anda kehilangan minat terhadap banyak hal?",
    "Apakah Anda merasa tidak berharga?",
    "Apakah Anda mempunyai pikiran untuk mengakhiri hidup Anda?",
    "Apakah Anda merasa lelah sepanjang waktu?",
    "Apakah Anda merasa tidak enak di perut?",
    "Apakah Anda mudah lelah?",
    "Apakah Anda minum alkohol lebih banyak dari biasanya atau menggunakan narkoba?",
    "Apakah Anda yakin seseorang mencoba mencelakai Anda dengan cara tertentu?",
    "Apakah ada yang mengganggu atau hal tidak biasa dalam pikiran Anda?",
    "Apakah Anda pernah mendengar suara tanpa tahu sumbernya atau yang orang lain tidak dengar?",
    "Apakah Anda bermimpi mengganggu tentang bencana/musibah atau seolah mengalaminya kembali?",
    "Apakah Anda menghindari kegiatan, tempat, orang, atau pikiran yang mengingatkan akan bencana tersebut?",
    "Apakah minat Anda terhadap teman dan kegiatan yang biasa berkurang?",
    "Apakah Anda merasa sangat terganggu dalam situasi yang mengingatkan akan bencana itu?",
    "Apakah Anda kesulitan mengalami atau mengekspresikan perasaan Anda?"
]

srq_items = []
for idx, q in enumerate(srq_questions):
    if idx < 20:
        scale = "GME"
    elif idx == 20:
        scale = "ZAT"
    elif idx < 24:
        scale = "PSIKOTIK"
    else:
        scale = "PTSD"
    srq_items.append({
        "text": f"{idx + 1}. {q}",
        "scale": scale
    })

srq_form = {
    "version": "SRQ-29-ID-KEMENKES-2026",
    "title": "Self-Reporting Questionnaire 29 (SRQ-29)",
    "instructions": "Bacalah setiap pertanyaan yang berhubungan dengan masalah yang mungkin mengganggu Anda selama 30 hari terakhir. Pilih Ya jika mengalaminya, atau Tidak jika tidak mengalaminya.",
    "sourceTitle": "Petunjuk Teknis Skrining Kesehatan Jiwa di Fasilitas Pelayanan Kesehatan · Kemenkes RI (2019)",
    "sourceUrl": "https://keswa.kemkes.go.id/",
    "sourceLocator": "PsikoTest/SRQ-29/SRQ-29_Acuan_dan_Skoring.md · §2–5",
    "choices": [
        "Tidak",
        "Ya"
    ],
    "values": [0, 1],
    "scales": [
        {
            "id": "GME",
            "label": "Gangguan Mental Emosional (GME)",
            "meaning": "Skor >= 5 menunjukkan indikasi distres emosional/cemas/depresi (Butir 1–20)."
        },
        {
            "id": "ZAT",
            "label": "Penggunaan Zat",
            "meaning": "Respons Ya pada Butir 21 menunjukkan indikasi penggunaan zat atau alkohol berlebih."
        },
        {
            "id": "PSIKOTIK",
            "label": "Gejala Psikotik",
            "meaning": "Respons Ya pada Butir 22–24 menunjukkan indikasi gangguan persepsi atau realitas."
        },
        {
            "id": "PTSD",
            "label": "Gejala Trauma (PTSD)",
            "meaning": "Respons Ya pada Butir 25–29 menunjukkan gejala distres pasca-trauma."
        }
    ],
    "items": srq_items,
    "average": False,
    "minimumAnswers": 29,
    "interpretationNote": "Skrining kesehatan jiwa Kemenkes RI. GME >= 5 menunjukkan indikasi distres mental emosional. Respons Ya pada butir 17, 21, 22–24, atau 25–29 memerlukan rujukan profesional."
}

# 3. VAK Form
vak_scenarios = [
    ("Ketika mengajari seseorang hal baru, Anda cenderung:", "Menulis instruksi atau menggambar diagram", "Menjelaskan secara lisan langkah demi langkah", "Memperagakan langsung dan mengajak mereka mencoba"),
    ("Ketika memahami maksud seseorang, Anda spontan berkata:", "'Saya melihat apa yang Anda maksud'", "'Saya mendengar apa yang Anda katakan'", "'Saya merasakan apa yang Anda rasakan'"),
    ("Ketika meminta petunjuk ke suatu tempat, Anda lebih suka:", "Melihat peta visual atau rute tertulis", "Mendengar instruksi arah lisan", "Berjalan langsung atau dipandu sambil bergerak"),
    ("Ketika mempelajari instruksi perakitan barang, Anda memilih:", "Melihat gambar skema dan panduan ilustrasi", "Mendengar penjelasan tutorial audio/video", "Langsung mencoba merakit bagian-bagian fisiknya"),
    ("Ketika menyampaikan komplain barang bermasalah, Anda:", "Menulis surat atau email terperinci", "Menelepon petugas untuk berbicara langsung", "Mendatangi toko secara fisik membawa barangnya"),
    ("Aktivitas waktu luang yang paling Anda sukai:", "Melihat pameran, galeri foto, atau membaca buku", "Mendengarkan musik, podcast, atau berbincang", "Berolahraga, berkebun, atau membuat kerajinan"),
    ("Saat membeli hadiah untuk sahabat, Anda cenderung memilih:", "Barang dengan tampilan visual/kemasan menarik", "Album musik atau tiket pertunjukan suara", "Benda praktis yang bisa langsung disentuh/dipakai"),
    ("Ketika berbelanja pakaian baru di toko, Anda:", "Melihat keserasian model dan warna di cermin", "Menanyakan pendapat pramuniaga atau teman", "Merasakan kenyamanan bahan saat dikenakan"),
    ("Ketika merencanakan liburan, hal pertama yang Anda lakukan:", "Melihat foto destinasi dan membaca brosur", "Mendengarkan rekomendasi cerita dari kerabat", "Membayangkan aktivitas fisik dan petualangan di sana"),
    ("Ketika mempertimbangkan membeli mobil baru, fokus utama Anda:", "Desain eksterior, warna, dan tampilan dashboard", "Keheningan kabin dan sistem audio kendaraan", "Kenyamanan suspensi dan sensasi uji kemudi (test drive)"),
    ("Ketika mengingat peristiwa masa lalu, Anda paling ingat:", "Pemandangan, wajah orang, dan warna ruangan", "Percakapan, suara musik, dan kata-kata yang diucapkan", "Perasaan emosional dan sensasi fisik saat itu"),
    ("Ketika menunggu dalam antrean yang panjang, Anda biasanya:", "Melihat sekeliling, membaca papan informasi atau hp", "Mengobrol dengan orang di dekat atau mendengar lagu", "Menggerakkan kaki, mengetuk jari, atau berjalan kecil"),
    ("Ketika berkonsentrasi pada tugas berat, Anda terganggu oleh:", "Kekacauan visual atau ruangan yang berantakan", "Suara bising, obrolan orang, atau dengung", "Kursi yang tidak nyaman atau keharusan diam terlalu lama"),
    ("Ketika menghafal nomor telepon baru, Anda:", "Membayangkan bentuk angka di kepala atau mencatatnya", "Mengulang nomor tersebut bersuara beberapa kali", "Mengetikkan tombol angka di layar secara refleks"),
    ("Gaya membaca buku favorit Anda:", "Fiksi deskriptif dengan banyak visualisasi adegan", "Buku dengan dialog hidup atau buku audio (audiobook)", "Buku aksi petualangan dengan plot yang dinamis"),
    ("Ketika mengekspresikan kemarahan, Anda cenderung:", "Memperlihatkan ekspresi wajah dingin atau menatap tajam", "Meninggikan nada bicara atau berteriak", "Membanting pintu, mengepalkan tangan, atau berjalan pergi"),
    ("Ketika merasa sangat gembira, reaksi spontan Anda:", "Tersenyum lebar dan mata berbinar", "Berseru kegirangan atau bernyanyi", "Melompat, bertepuk tangan, atau memeluk orang lain"),
    ("Ketika bertemu kenalan lama, hal yang pertama Anda ingat:", "Wajahnya, tetapi sering lupa namanya", "Namanya, tetapi agak lupa wajahnya", "Kegiatan atau pengalaman bersama yang pernah dilakukan"),
    ("Ketika menonton film, Anda paling terkesan dengan:", "Tata visual, sinematografi, dan efek khusus", "Dialog tokoh, musik latar, dan lagu tema", "Adegan aksi, gerakan mendebarkan, dan ketegangan emosional"),
    ("Dalam diskusi kelompok, peran yang paling alami bagi Anda:", "Mencatat poin di papan tulis atau membuat mind map", "Menyampaikan ide secara verbal dan menyimak argumen", "Mengatur giliran, membagikan kertas, atau bergerak aktif"),
    ("Ketika menjelaskan ide rumit kepada rekan kerja, Anda:", "Menggambar sketsa atau bagan alur di kertas", "Menjelaskan dengan analogi dan intonasi suara", "Memakai peraga fisik atau gestur tangan yang kuat"),
    ("Ketika memasak resep baru, Anda lebih suka:", "Mengikuti foto langkah-langkah di buku resep", "Mendengarkan arahan dari orang yang berpengalaman", "Bereksperimen mencicipi dan mengaduk dengan tangan"),
    ("Ketika mengunjungi museum atau pameran sains, Anda paling tertarik:", "Membaca plakat penjelasan dan melihat replika", "Mendengarkan panduan audio tour pemandu", "Menekan tombol alat peraga interaktif dan mencoba simulasi"),
    ("Ketika mencoba mengingat nama seseorang yang baru dikenal:", "Mengingat kartu nama atau ejaan huruf namanya", "Mengulang namanya beberapa kali saat berbicara", "Mengingat jabatan tangan atau suasana pertemuan"),
    ("Ketika memilih makanan di restoran baru, Anda:", "Melihat foto menu yang paling menggugah selera", "Meminta rekomendasi lisan dari pelayan", "Memilih makanan yang teksturnya paling Anda sukai"),
    ("Saat mempersiapkan ujian penting, cara belajar paling efektif bagi Anda:", "Membuat rangkuman warna-warni dan membaca catatan", "Mendiskusikan materi bersama teman atau merekam suara", "Mengerjakan latihan soal langsung dan simulasi ujian"),
    ("Ketika menghadapi masalah teknis pada perangkat lunak/aplikasi:", "Mencari petunjuk diagram dan tangkapan layar solusi", "Menghubungi layanan bantuan suara customer service", "Mengklik berbagai tombol untuk mencoba langsung solusinya"),
    ("Ketika mendengarkan ceramah atau seminar panjang, Anda:", "Fokus melihat slide presentasi pembicara", "Menyimak seksama kata demi kata yang diucapkan", "Mencoret-coret kertas atau sering mengubah posisi duduk"),
    ("Ketika berada di tempat baru untuk pertama kalinya, Anda:", "Mencari peta denah lokasi atau papan petunjuk jalan", "Bertanya langsung kepada petugas keamanan setempat", "Berjalan menyusuri lorong untuk merasakan suasananya"),
    ("Secara keseluruhan, Anda merasa paling cepat menguasai keahlian baru bila:", "Melihat demonstrasi visual yang jelas dan teratur", "Mendengar instruksi terperinci dari mentor", "Langsung mempraktikkannya sendiri dengan tangan")
]

vak_items = []
for idx, (q, opt_a, opt_b, opt_c) in enumerate(vak_scenarios):
    vak_items.append({
        "text": f"{idx + 1}. {q}",
        "scale": "preference",
        "choices": [
            f"A. {opt_a} (Visual)",
            f"B. {opt_b} (Auditori)",
            f"C. {opt_c} (Kinestetik)"
        ]
    })

vak_form = {
    "version": "VAK-30-ID-2026",
    "title": "Visual, Auditory, Kinesthetic (VAK)",
    "instructions": "Pilihlah salah satu opsi A, B, atau C yang paling menggambarkan cara Anda merespons, mengingat, atau melakukan sesuatu dalam situasi berikut.",
    "sourceTitle": "VAK Learning Styles Assessment · Chislett & Chapman (2005); Adaptasi Indonesia (JKP, 2025)",
    "sourceUrl": "https://www.proedge-asso.com/oldsite/FNews_Articales/VAK.pdf",
    "sourceLocator": "PsikoTest/VAK/VAK_Acuan_dan_Skoring.md · §2–5",
    "choices": [
        "A. Visual (Melihat / Diagram / Tulisan)",
        "B. Auditori (Mendengar / Diskusi / Penjelasan Lisan)",
        "C. Kinestetik (Mempraktikkan Langsung / Sentuhan / Gerak)"
    ],
    "values": [1, 2, 3],
    "scales": [
        {
            "id": "preference",
            "label": "Gaya Belajar Sensorik",
            "meaning": "Proporsi pilihan A (Visual), B (Auditori), dan C (Kinestetik) dari 30 butir situasi."
        }
    ],
    "items": vak_items,
    "average": False,
    "minimumAnswers": 30,
    "interpretationNote": "Inventori gaya belajar VAK mendeskripsikan preferensi sensorik dalam menyerap informasi: Visual, Auditori, atau Kinestetik. Bukan tolok ukur batas kecerdasan IQ."
}

# 4. AQ Form (15 items from Soal AQ.pdf)
aq_scenarios = [
    (
        "1. Bagi saya, kesulitan adalah :",
        "Sesuatu yang harus dihindari",
        "Sesuatu yang bisa dicoba untuk dihadapi",
        "Bagian dari hidup. Menghindari kesulitan sama saja dengan menghindari kehidupan"
    ),
    (
        "2. Keyakinan saya terhadap masa depan adalah :",
        "Saya tidak berani menjamin, saya akan menunggu dan melihat bagaimana nantinya",
        "Melihat kemampuan yang sekarang ini, saya cukup yakin dengan masa depan saya",
        "Saya belum seberapa yakin, tetapi saya berharap bisa berhasil"
    ),
    (
        "3. Jika saya dihadapkan pada suatu masalah yang menurut saya sangat sulit dicapai keberhasilannya maka tanggapan saya adalah :",
        "Saya akan mengerjakannya, tidak peduli betapapun sulitnya",
        "Saya akan mencoba, namun jika keadaannya bertambah buruk saya akan berhenti",
        "Saya tidak mau mengerjakannya, karena ini mustahil bisa berhasil"
    ),
    (
        "4. Saya adalah orang yang :",
        "Kadang-kadang menyerah tergantung situasi dan kondisi",
        "Mudah menyerah",
        "Pantang menyerah"
    ),
    (
        "5. Bagi saya, pengembangan diri adalah :",
        "Cukup perlu",
        "Hal yang tidak seberapa perlu",
        "Segalanya"
    ),
    (
        "6. Sikap saya terhadap perubahan adalah :",
        "Saya selalu menerima perubahan yang ditawarkan kepada saya",
        "Saya agak sulit menyesuaikan diri dengan perubahan yang terjadi",
        "Saya akan lihat dulu perubahan yang seperti apa, baru saya bisa memutuskannya"
    ),
    (
        "7. Saya tergolong sebagai orang yang :",
        "Menunjukkan beberapa usaha jika memang diperlukan",
        "Ambisius",
        "Santai, tidak terlalu berambisi"
    ),
    (
        "8. Di antara tiga hal di bawah ini, yang paling sesuai dengan keadaan diri saya :",
        "Saya sudah puas dengan kehidupan saya sekarang ini",
        "Dalam hal tertentu saya sudah puas",
        "Masih banyak hal yang perlu saya capai"
    ),
    (
        "9. Jika menghadapi masalah yang sangat sulit saya cenderung :",
        "Menghadapi dan menyelesaikan hingga saya berhasil",
        "Menghindarinya",
        "Menghadapinya namun jika merasa tidak mampu maka saya akan berhenti"
    ),
    (
        "10. Saya adalah orang yang :",
        "Sangat suka tantangan / hal baru",
        "Suka melakukan hal-hal yang sudah ada/ tersedia/ sebagaimana mestinya",
        "Mencoba sesuatu yang baru"
    ),
    (
        "11. Jika lingkungan menawarkan sesuatu yang baru bagi saya :",
        "Saya cenderung menolaknya",
        "Saya akan mencobanya tetapi tidak menjamin keberhasilannya",
        "Saya langsung ikut serta dan melakukan segala hal hingga mencapai keberhasilannya"
    ),
    (
        "12. Slogan yang paling sesuai dengan diri saya adalah :",
        "Lakukan sekarang atau tidak sama sekali",
        "Nikmati hidup ini bersama teman selagi masih muda",
        "Pertimbangkan dulu masak-masak baru mengambil langkah"
    ),
    (
        "13. Orang yang bertanggung jawab terhadap kemajuan diri saya adalah :",
        "Lingkungan dan diri saya",
        "Lingkungan saya (keluarga, saudara, teman, dll)",
        "Sepenuhnya diri saya"
    ),
    (
        "14. Saya termotivasi untuk melakukan sesuatu jika :",
        "Saya merasa mampu",
        "Saya telah mengevaluasi kemampuan saya",
        "Walaupun merasa tidak mampu, saya tetap akan melakukannya"
    ),
    (
        "15. Kebanyakan waktu luang, saya gunakan untuk :",
        "Membaca buku",
        "Menghadiri seminar/ pelatihan dan mengikuti kegiatan-kegiatan di luar",
        "Kumpul bersama teman"
    )
]

aq_items = []
for idx, (q, opt_a, opt_b, opt_c) in enumerate(aq_scenarios):
    aq_items.append({
        "text": q,
        "scale": "aq_style",
        "choices": [
            f"a. {opt_a}",
            f"b. {opt_b}",
            f"c. {opt_c}"
        ]
    })

aq_form = {
    "version": "AQ-15-ID-PAKET25-2026",
    "title": "Adversity Quotient (AQ)",
    "instructions": "Pilihlah jawaban (a, b, atau c) yang paling menggambarkan reaksi spontan Anda pada 15 situasi berikut.",
    "sourceTitle": "Adversity Quotient (AQ) Adaptasi Indonesia · Stoltz (1997) & Bank PsikoTest Paket 25 Item",
    "sourceUrl": "https://www.peaklearning.com/about-aq/",
    "sourceLocator": "PsikoTest/Adversity_Quotient/Adversity_Quotient_Acuan_dan_Skoring.md · §4B",
    "choices": [
        "Pilihan a",
        "Pilihan b",
        "Pilihan c"
    ],
    "values": [1, 2, 3],
    "scales": [
        {
            "id": "aq_style",
            "label": "Tipologi Daya Juang",
            "meaning": "Kecenderungan respons menghadapi kesulitan: Quitter, Camper, atau Climber."
        }
    ],
    "items": aq_items,
    "average": False,
    "minimumAnswers": 15,
    "interpretationNote": "Menggambarkan tipologi daya juang: Climber (tahan banting & pantang menyerah), Camper (bertahan di zona nyaman), atau Quitter (mudah menyerah)."
}

# Update catalog
forms_map = {
    "bdi": bdi_form,
    "srq-29": srq_form,
    "vak": vak_form,
    "adversity_quotient": aq_form
}

for inst in catalog:
    if inst["id"] in forms_map:
        inst["form"] = forms_map[inst["id"]]
        print(f"Updated form for: {inst['id']}")

with open(catalog_path, "w", encoding="utf-8") as f:
    json.dump(catalog, f, ensure_ascii=False, indent=2)

print("Catalog JSON updated successfully.")
