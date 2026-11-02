import android.icu.text.Transliterator;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** One-off content conversion using Android's installed ICU; no runtime library is added to the app. */
public class PoetryPinyin {
  static final String[][] READINGS={
    {"参与商","shēn yǔ shāng"},{"参商","shēn shāng"},{"参差","cēn cī"},{"单于","chán yú"},{"龟兹","qiū cí"},
    {"万重山","wàn chóng shān"},{"几重","jǐ chóng"},{"夜千重","yè qiān chóng"},{"重帷","chóng wéi"},{"一日还","yī rì huán"},
    {"将进酒","qiāng jìn jiǔ"},{"将进","qiāng jìn"},{"燕台","yān tái"},{"燕山","yān shān"},{"燕然","yān rán"},{"幽燕","yōu yān"},
    {"大宛","dà yuān"},{"渔阳鼙鼓","yú yáng pí gǔ"},{"千乘","qiān shèng"},{"金仆姑","jīn pú gū"},{"欸乃","ǎi nǎi"},
    {"噫吁戏","yī xū xī"},{"噫吁嚱","yī xū xī"},{"萋萋","qī qī"},{"葳蕤","wēi ruí"},{"薜荔","bì lì"},{"薜帷","bì wéi"},
    {"菡萏","hàn dàn"},{"筚篥","bì lì"},{"觱篥","bì lì"},{"鹧鸪","zhè gū"},{"鄜州","fū zhōu"},{"荦确","luò què"},
    {"滁州","chú zhōu"},{"绿绮","lǜ qǐ"},{"辋川","wǎng chuān"},{"盱眙","xū yí"},{"潺湲","chán yuán"},{"东菑","dōng zī"},
    {"不觉","bù jué"},{"觉来","jué lái"},{"衣裳","yī cháng"},{"更深","gēng shēn"},{"五更","wǔ gēng"},{"三更","sān gēng"},
    {"朝朝","zhāo zhāo"},{"朝辞","zhāo cí"},{"朝闻","zhāo wén"},{"朝如","zhāo rú"},{"朝露","zhāo lù"},{"朝雨","zhāo yǔ"},
    {"明朝","míng zhāo"},{"朝来","zhāo lái"},{"今朝","jīn zhāo"},{"朝天","cháo tiān"},{"朝回","cháo huí"},{"早朝","zǎo cháo"},
    {"空馀","kōng yú"},{"馀雪","yú xuě"},{"行行","xíng xíng"},{"雁行","yàn háng"},{"数行","shù háng"},{"几行","jǐ háng"},
    {"黄鹂","huáng lí"},{"箜篌","kōng hóu"},{"啾啾","jiū jiū"},{"蹙蛾眉","cù é méi"},{"供养","gōng yǎng"},{"不胜","bù shèng"}
    ,
    {"长","cháng"},{"地","dì"},{"朝","zhāo"},{"曲","qǔ"},{"贾","gǔ"},{"还","huán"},{"为","wéi"},{"薄","bó"},{"弹","tán"},{"媕婀","ān ē"},{"窅冥","yǎo míng"},{"新授箓","xīn shòu lù"},
    {"白发","bái fà"},{"鬓发","bìn fà"},{"插发","chā fà"},{"散发","sàn fà"},{"妾发","qiè fà"},{"华发","huá fà"},{"头发","tóu fà"},{"发如丝","fà rú sī"},{"鬓云","bìn yún"},
    {"长所育","zhǎng suǒ yù"},{"桐叶长","tóng yè zhǎng"},{"长成","zhǎng chéng"},{"年长","nián zhǎng"},{"长者","zhǎng zhě"},{"长大","zhǎng dà"},{"生长明妃","shēng zhǎng míng fēi"},
    {"为君","wèi jūn"},{"为此","wèi cǐ"},{"为簪组","wéi zān zǔ"},{"为静","wèi jìng"},{"为我","wèi wǒ"},{"为人爱惜","wèi rén ài xī"},{"为人鞿","wéi rén jī"},{"为之","wèi zhī"},{"为摩挲","wèi mó suō"},{"为感","wèi gǎn"},{"为谁","wèi shuí"},{"为王孙","wèi wáng sūn"},{"为表","wèi biǎo"},{"为取","wèi qǔ"},{"为乘","wèi chéng"},{"为问","wèi wèn"},{"为护","wèi hù"},{"为探看","wèi tàn kān"},{"为他人","wèi tā rén"},{"谁为含愁","shuí wèi hán chóu"},{"为有","wèi yǒu"},{"为离人","wèi lí rén"},{"为听","wéi tīng"},{"贾谊","jiǎ yì"},{"贾生","jiǎ shēng"},
    {"还相遇","hái xiāng yù"},{"还复去","hái fù qù"},{"还见","hái jiàn"},{"还合","hái hé"},{"还独倾","hái dú qīng"},{"还从","hái cóng"},{"还复来","huán fù lái"},{"还戍边","hái shù biān"},{"还来","huán lái"},{"还将","hái jiāng"},{"还作","hái zuò"},{"晓还密","xiǎo hái mì"},{"还欲","hái yù"},{"还祠庙","huán cí miào"},{"还对镜","hái duì jìng"},{"还惜别","hái xī bié"},
    {"重深","chóng shēn"},{"重上","chóng shàng"},{"重阳","chóng yáng"},{"九重","jiǔ chóng"},{"重寄","chóng jì"},{"重开","chóng kāi"},{"重唧唧","chóng jī jī"},{"重闻","chóng wén"},{"重把","chóng bǎ"},{"百重","bǎi chóng"},{"重相见","chóng xiāng jiàn"},{"影重","yǐng chóng"},{"重遮","chóng zhē"},{"重问","chóng wèn"},{"万重","wàn chóng"},{"重来","chóng lái"},
    {"朝东","cháo dōng"},{"朝玉京","cháo yù jīng"},{"朝河宗","cháo hé zōng"},{"朝班","cháo bān"},{"朝贺","cháo hè"},{"中朝","zhōng cháo"},{"朝四夷","cháo sì yí"},{"汉朝","hàn cháo"},{"圣朝","shèng cháo"},{"三朝","sān cháo"},{"南朝","nán cháo"},{"朝罢","cháo bà"},{"朝衣","cháo yī"},{"两朝","liǎng cháo"},{"朝庭","cháo tíng"},{"朝至尊","cháo zhì zūn"},{"前朝","qián cháo"},{"六朝","liù cháo"},
    {"独数","dú shǔ"},{"数上","shuò shàng"},{"肯数","kěn shǔ"},{"数奇","shù jī"},{"数问","shuò wèn"},{"数花朵","shǔ huā duǒ"},{"占梦数","zhān mèng shuò"},
    {"扪参","mén shēn"},{"更坐","gèng zuò"},{"更隔","gèng gé"},{"更教","gèng jiāo"},{"更有","gèng yǒu"},{"更添","gèng tiān"},{"称意","chèn yì"},{"称心","chèn xīn"},{"乐器","yuè qì"},{"此乐","cǐ yuè"},{"清乐","qīng yuè"},{"长乐","cháng lè"},
    {"一曲","yī qǔ"},{"数曲","shù qǔ"},{"曲罢","qǔ bà"},{"曲终","qǔ zhōng"},{"曲转","qǔ zhuǎn"},{"曲似","qū sì"},{"委曲","wěi qū"},{"曲江","qǔ jiāng"},{"睡觉","shuì jué"},{"觉时","jué shí"},{"作客","zuò kè"},{"舍弟","shè dì"},{"客舍","kè shè"},{"六么","liù yāo"},{"好为","hào wéi"},{"好还乡","hǎo huán xiāng"},{"看花","kàn huā"},{"看取","kàn qǔ"},{"横行","héng xíng"},{"行伍","háng wǔ"},{"琵琶行","pí pá xíng"},{"薄暮","bó mù"},{"薄命","bó mìng"},{"薄几重","báo jǐ chóng"},{"宁久微","nìng jiǔ wēi"},{"宁不知","nìng bù zhī"},{"宁知","nìng zhī"}
    ,{"看","kàn"},{"塞","sài"},{"没","mò"},{"调","diào"},{"著","zhuó"},{"衣","yī"},
    {"冰塞川","bīng sè chuān"},{"著花","zhuó huā"},{"文章著","wén zhāng zhù"},{"幽咽","yōu yè"},{"声咽","shēng yè"},{"填咽","tián yān"},{"调角","diào jiǎo"},{"成行","chéng háng"},{"两行","liǎng háng"},{"点行频","diǎn háng pín"},{"太行","tài háng"},{"高行","gāo xìng"},{"燕歌行","yān gē xíng"},{"燕家","yān jiā"},{"天寒翠袖薄","tiān hán cuì xiù báo"},{"锦衾薄","jǐn qīn báo"},{"轻罗已薄","qīng luó yǐ báo"},{"霓裳","ní cháng"},{"玉搔头","yù sāo tóu"},{"执手","zhí shǒu"},{"泥他","nì tā"},{"弹指","tán zhǐ"},{"一宿","yī xiǔ"},{"星宿","xīng xiù"},{"宿雨","sù yǔ"},{"白日放歌","bái rì fàng gē"},{"一觉","yī jiào"},{"强欲","qiǎng yù"},{"白袷衣","bái jiá yī"},{"葳蕤","wēi ruí"},{"听蜀僧濬","tīng shǔ sēng jùn"}
    ,{"尽","jìn"},{"似","sì"},{"扁舟","piān zhōu"},{"为君妇","wéi jūn fù"},{"更漏","gēng lòu"},{"更鼓","gēng gǔ"},{"更阑","gēng lán"},{"更残","gēng cán"},{"五更","wǔ gēng"},{"难识","nàn shí"},{"多难","duō nàn"},{"家难","jiā nàn"},{"北斗","běi dǒu"},{"斗酒","dǒu jiǔ"},{"斗柄","dǒu bǐng"},{"千斗","qiān dǒu"},{"十斗","shí dǒu"},{"三斗","sān dǒu"},{"应怜","yīng lián"},{"曾经","céng jīng"},{"不曾","bù céng"},{"未曾","wèi céng"},{"少年","shào nián"},{"少小","shào xiǎo"},{"少壮","shào zhuàng"},{"少陵","shào líng"},{"少府","shào fǔ"},{"解落","jiě luò"},{"凝绝","níng jué"},{"将相","jiàng xiàng"},{"丞相","chéng xiàng"},{"石棱","shí léng"},{"将军","jiāng jūn"},{"相公","xiàng gōng"},{"月落","yuè luò"},{"度量","duó liáng"},{"独坐","dú zuò"}
  };
  public static void main(String[] args)throws Exception{
    Transliterator t=Transliterator.getInstance("Han-Latin");
    Map<String,String> readings=new HashMap<>();for(String[] pair:READINGS)readings.put(pair[0],pair[1]);
    List<String> words=new ArrayList<>(readings.keySet());words.sort((a,b)->Integer.compare(b.length(),a.length()));
    Pattern pattern=Pattern.compile(String.join("|",words.stream().map(Pattern::quote).toArray(String[]::new)));
    JSONObject document=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
    JSONArray poems=document.getJSONArray("books").getJSONObject(0).getJSONArray("poems");int count=0;
    for(int i=0;i<poems.length();i++){
      JSONArray stanzas=poems.getJSONObject(i).getJSONArray("stanzas");
      for(int j=0;j<stanzas.length();j++){
        JSONArray lines=stanzas.getJSONObject(j).getJSONArray("lines");
        for(int k=0;k<lines.length();k++){
          JSONObject line=lines.getJSONObject(k);String original=line.getString("original");
          Matcher m=pattern.matcher(original);StringBuilder out=new StringBuilder();int end=0;
          while(m.find()){out.append(t.transliterate(original.substring(end,m.start()))).append(' ').append(readings.get(m.group())).append(' ');end=m.end();}
          out.append(t.transliterate(original.substring(end)));line.put("pinyin",out.toString().trim().replaceAll(" +"," ").replaceAll(" ?[.]", "。").replaceAll("\\s+([，。！？；：、])", "$1"));count++;
        }
      }
    }
    Files.write(Paths.get(args[1]),document.toString(2).getBytes(StandardCharsets.UTF_8));
    System.out.println("Transliterated "+count+" original lines with system ICU");
  }
}
