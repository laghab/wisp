#!/usr/bin/env python3
"""Generates app/src/main/assets/wordlists/hinglish.tsv: common Hinglish (Hindi in Latin letters) with a probability 0-255.

Hinglish has no standard spelling, so several spellings of the same word are listed (kya/kyaa, nahi/nahin/nhi). Words earlier
in the tiers are more common. English words are not listed here: the Hinglish language also reads the English dictionary.
Add words by appending to a tier and re-running:  python3 tools/hinglish_wordlist.py
"""
import pathlib

TIERS = [
  (215, """hai hain tha thi the ho hoga hogi hoon hu ka ki ke ko se me mein par pe ne ye yeh wo woh aur ya to toh bhi nahi nahin na kya
kaise kaisa kaisi kab kahan kahaan kaun kyun kyu kyon kitna kitne kitni main mai mujhe mujhko mera meri mere hum hume humein hamara hamare
tum tumhe tumhein tumhara tumhari tumhare tera teri tere aap aapko aapka aapki aapke uska uski uske unka unki unke inka isko usko unko
yahan yaha wahan waha idhar udhar abhi ab phir fir kal aaj parso jab tab agar lekin magar par kyunki isliye bas sirf bahut bohot zyada
thoda thodi kam sab sabhi koi kuch kuchh har ek do teen char chaar paanch panch chhe saat aath nau das haan han ha ji"""),
  (195, """kar karo karna karta karti karte kiya kiye kiyo karenge karunga karungi karega karegi rahe raha rahi rahega rahegi
ja jao jana jata jati jate gaya gayi gaye jaunga jayega jayegi chalo chal chalna chala chali chalte aa aao aana aata aati aate aaya aayi aaye
aaunga aayega de do dena deta deti dete diya diye dunga dega degi le lo lena leta leti lete liya liye lunga lega legi
bol bolo bolna bolta bolti bolte bola boli bataya bata batao batana batata batati dekh dekho dekhna dekha dekhi dekhte dekhenge
sun suno sunna suna suni sunte samajh samjha samjhi samajhna samajhta samajhti pata jaanta jaanti jaante maloom chahiye chahta chahti chahte
mil milna mila mili mile milte milenge lagta lagti lagte laga lagi lage rakh rakho rakhna rakha rakhi ruk ruko rukna ruka
baith baitho baithna baitha uth utho uthna utha so soja sona soya khao khana khaya khaye kha pee peena piya pilo
likh likho likhna likha padh padho padhna padha khel khelo khelna khela bhej bhejo bhejna bheja bulao bulana bulaya
kholo khol khula band bandh mat naa hota hoti hote hua hui hue honge hongi ho gaya ho gayi tha thi theek thik"""),
  (170, """ghar kaam log din raat subah shaam dopahar paisa paise rupaye rupay kitab school college office dost yaar bhai behen bahan bhaiya didi
mummy papa maa baap beta beti bachcha bachche bacche ladka ladki aadmi aurat shaadi shadi pyaar pyar mohabbat dil dimaag zindagi
duniya waqt samay baat baatein sawal jawab khabar kahani gana gaana film phone mobile number message call photo video gaadi gadi
bike car bus train station road sheher shehar gaon desh paani pani chai coffee roti chawal sabzi dal doodh nashta lunch dinner
mithai naam kaam wala wali wale jaisa jaise jaisi waisa waise waisi aisa aise aisi kaisa itna itne itni utna utne utni
accha acha achha achhi acchi bura buri bada badi bade chota choti chhota chhoti chhote naya nayi naye purana purani purane
sahi galat mushkil aasan asaan sundar khoobsurat mast zabardast bekaar jaldi dheere dhire seedha sidha ulta pura poora adhura
khush udaas naraz gussa pareshaan thak thaka thaki bhookh pyaas neend mazaa maza majboor
arre arey are oye wah waah shabash hmm bilkul zaroor shayad pakka sach jhoot kasam please sorry thanks shukriya dhanyavad namaste
namaskar alvida suprabhat shubh ratri subhah kal subah raat parso""") ,
  (140, """aadat aakhir aaj kal aaraam aasman aankh aankhein abba amma anda ankit apna apni apne baad baar baaki baje bajao bana banana banaya
banti bante baras barish bata bhai bhaag bhaage bhagwan bhi bheed bhool bhoola bhoolna bhukh bichar bijli bimar bimaar bol bolo
chaabi chahe chai chakkar chalega chalegi chalenge chaar chehra chhod chhodo chhodna chhoda chhuti chhutti chinta chor chup chupchap
dard darr darna dekhna deewar dhanda dhoka dhoop dhyan dikhana dikha dikhao dikhta dikhti dil dilli diwali doosra dusra dusri doosri
dukaan dukan dost doston dua dukh dum dunia duniya duniyadari fark farak fayda fursat gareeb ghabra ghalti ghanta ghante gila goli
gussa haath hafta hamesha hamare haq haqeeqat hasna hansna hasi hawa hazaar hazar himmat hisaab hona hota hoti idhar ijazat ilaaj
insaan intezaar intezar ishq ishaara ismein isme isse issey itna jaan jagah jahan jahaan jaldi jalna jawaan jeet jeena jeeta jeete
jhagda jhooth jo jisko jiska jiski jiske jin jinko jise jitna jitne jitni jungle kabhi kabhee kachha kahin kahi kahna kehna kehte kehta kehti
kaha kahan kal kaafi kafi kaam kaamyaab kab kabhi kaise kamra kamzor kamar kampyuter kanoon kapde kapda karib kareeb kaun kaunsa
kehna khabar khaas khana khareed kharid kharcha khatam khatra khel khet khidki khoob khoon khubsurat khushi khwab khyal kiska kiski kiske
kisi kisko kisne kitaab kitni kyaa kyunki laga lagao laana lana laya laye leher lekar liye likhe logon maafi maaf maalik maalum
maange maangna maan maana mahina mahine majak majaak makaan malum mann marna mara mari mare masti matlab mausam mehnat mehengi mehenga
meetha mere milna mitra mohalla mujhse mujhme mukhya mumkin mushkil muskaan muskurana naa naach nafrat nahaana nahana naraaz nasha
nazar nazdeek neeche neeche nikal nikalna nikla nikli nikle nishaan niyam nokri nukkad paas pachaas padosi pagal pahle pehle pahla pehla
paisa pakad pakda pakadna palat paani parivar pareshani parda parhez pasand pasandida patla patli patang pehchaan pehchan
pehle phal phir phool phuljhadi pichhe peeche pita pitaji pooch poochna poocha pooja poori pooree pura pyaara pyara pyari
raasta rasta raaz raha rahi rahna rakshabandhan ramzan rang rangeen rishta rishte rona rota roti rukawat saaf saal saath sabr
sach sachchi sabzi sadak safar sahab sahi sakta sakti sakte samne samaan sambhal sambhalna sapna sapne sarkar saste sasta sasti
sau sawaal seekh seekhna seekha sehat sharam sharma shaayad shant shuru shukriya shaadi sirf sitaare soch socha sochna sochte
sona soch subah sunehri swaad tabiyat taiyaar tayyar takleef takkar tamasha tang tareeka tarah tareef tasveer teez tehwar thakaan
thakan tham thandi thanda thande theek thodi tohfa toota tootna tum tumhara tyohar udhar umar umeed umr unhe unhein unse upar
uska utar utarna vaada vaah vajah waapas wapas waqt wahi wajah yaad yaar yaaron yakeen zaban zameen zarurat zaroorat zindagi""".replace("\n"," ")),
  (115, """kyaa kyu kyon nhi nahee nai nahin hn hnn haa haanji achaa acha thik hai thikk theek hai ok okay k kk haan haan yaa yaar yr bro bhai
bhai sahab bhaisahab mjhe mujhe mujhse tmhe tumhe tmhari tumhari ap apka apki abi abhi bhut bhot bahot bohot jyada jada thda thoda
kuchh kch kuch kaise kese kse kaisa kesa kya hua kya hai kaha kaha gya gayi gye gaye aya aye aaye rha rhi rhe raha rahi rahe
krna karna krta krti krte krega karega mtlb matlab pta pata jldi jaldi kal kl aaj aj abhi bs bas sch sach shyd shayad
plz pls please thnx thanks tq tnx shukriya dhanyawad bye tata byee gn gm goodnight morning suprabhat""".replace("\n"," ")),
]

words = {}
for prob, block in TIERS:
    for w in block.split():
        w = w.strip().lower()
        if len(w) >= 1 and (w not in words or words[w] < prob):
            words[w] = prob

out = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/wordlists/hinglish.tsv"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("".join(f"{w}\t{p}\n" for w, p in sorted(words.items(), key=lambda kv: (-kv[1], kv[0]))), encoding="utf-8")
print(f"{len(words)} words -> {out}")
