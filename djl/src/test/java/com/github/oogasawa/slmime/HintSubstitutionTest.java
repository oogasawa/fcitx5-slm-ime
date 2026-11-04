package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The outputs are those the model of version 3 wrote with these hints (greedy, as the server decodes). */
class HintSubstitutionTest {

    private static List<DictionaryHints.Hint> hints(String... pairs) {
        List<DictionaryHints.Hint> list = new ArrayList<>();
        for (String pair : pairs) {
            String[] rw = pair.split("=");
            list.add(new DictionaryHints.Hint(rw[0], rw[1]));
        }
        return list;
    }

    @Test
    void aWordTheModelCouldNotWriteIsPutIn() {
        assertEquals("阿諛追従が|過ぎる",
            HintSubstitution.apply("あゆついしょうがすぎる", "アユ追唱が|過ぎる", hints("あゆついしょう=阿諛追従")));
        assertEquals("肝性昏睡に対しては", HintSubstitution.apply("かんせいこんすいにたいしては", "完成昏睡に対しては",
            hints("かんせいこんすい=肝性昏睡")));
    }

    @Test
    void aWordTheModelCutInTwoIsPutIn() {
        assertEquals("多発肝膿瘍または|肺癌の|多発肝転移を|疑い",
            HintSubstitution.apply("たはつかんのうようまたははいがんのたはつかんてんいをうたがい",
                "多発間|農用または|肺癌の|多発寒天医を|疑い",
                hints("たはつかんのうよう=多発肝膿瘍", "たはつかんてんい=多発肝転移")));
    }

    @Test
    void aWordOfTheDictionaryTheModelWroteStays() {
        assertEquals("リンパ節腫大は", HintSubstitution.apply("りんぱせつしゅだいは", "リンパ節腫大は",
            hints("りんぱせつしゅだい=リンパ節腫大", "りんぱせつしゅだい=ＬＮ腫大", "りんぱせつしゅ=リンパ節腫")));
        assertEquals("充電器が", HintSubstitution.apply("じゅうでんきが", "充電器が",
            hints("じゅうでんき=充電器", "じゅうでんき=重電機", "じゅうでんき=重電気")));
    }

    @Test
    void aHintAcrossTheBoundsOfAWordChangesNothing() {
        // the kana か is the particle of the model's text
        assertEquals("影響から", HintSubstitution.apply("えいきょうから", "影響から", hints("えいきょうか=影響下")));
        // the reading of the hint starts inside a bunsetsu
        assertEquals("治療方針が", HintSubstitution.apply("ちりょうほうしんが", "治療方針が", hints("りょうほうし=療法士")));
        // the model's text is longer than the word: a phrase that holds the reading
        assertEquals("人民委員部|参事。", HintSubstitution.apply("じんみんいいんぶさんじ。", "人民委員部|参事。",
            hints("じんみんいいん=人民委員")));
    }

    @Test
    void aWordAfterRightOrLeftIsPutInWithIt() {
        assertEquals("右下葉無気肺を|認めます。", HintSubstitution.apply("うかようむきはいをみとめます。", "羽化用向き肺を|認めます。",
            hints("かようむきはい=下葉無気肺")));
        assertEquals("右下葉無気肺を|認めます。", HintSubstitution.apply("うかようむきはいをみとめます。", "右下葉無気肺を|認めます。",
            hints("かようむきはい=下葉無気肺")));
        // not after another kana
        assertEquals("下下葉無気肺を", HintSubstitution.apply("げかようむきはいを", "下下葉無気肺を", hints("かようむきはい=下葉無気肺")));
    }

    @Test
    void kanaTheModelWroteOnPurposeStays() {
        assertEquals("ご了承ください。", HintSubstitution.apply("ごりょうしょうください。", "ご了承ください。",
            hints("ごりょうしょう=御了承")));
    }

    @Test
    void withoutHintsTheOutputStays() {
        assertEquals("今日は|いい|天気ですね", HintSubstitution.apply("きょうはいいてんきですね", "今日は|いい|天気ですね", List.of()));
    }
}
