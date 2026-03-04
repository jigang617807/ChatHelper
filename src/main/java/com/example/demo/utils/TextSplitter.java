package com.example.demo.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextSplitter {

    /**
     * 智能递归文本切分器
     * 优先按段落切分，其次按句子切分，最后按字符切分。
     * 
     * @param text 原始文本
     * @param maxChunkSize 最大块大小 (推荐 500-1000)
     * @param overlap 重叠大小 (推荐 100-200)
     * @return 切分后的文本列表
     */
    public static List<String> splitText(String text, int maxChunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        // 递归切分
        splitRecursive(text, maxChunkSize, overlap, chunks);
        return chunks;
    }

    private static void splitRecursive(String text, int maxChunkSize, int overlap, List<String> chunks) {
        if (text.length() <= maxChunkSize) {
            chunks.add(text);
            return;
        }

        // 定义分隔符优先级：段落 -> 句子 -> 逗号 -> 空格
        String[] separators = {"\n\n", "\n", "。", "！", "？", ".", "!", "?", "；", ";", "，", ",", " "};

        int splitPoint = -1;

        for (String separator : separators) {
            // 尝试在这个分隔符上切分
            // 我们希望切分点尽可能靠近 maxChunkSize，但不能超过它
            // 实际上，我们应该找一个 "中间点"，或者找一个 "使得第一块长度 <= maxChunkSize 的最大点"
            
            int lastIndex = text.lastIndexOf(separator, maxChunkSize);
            
            // 如果找到了分隔符，并且这个分隔符不在开头太近的位置（避免切出空块）
            if (lastIndex > 0) {
                splitPoint = lastIndex + separator.length(); // 包含分隔符
                break;
            }
        }

        // 如果所有分隔符都找不到（或者都在 maxChunkSize 之后），只能强制切分
        if (splitPoint == -1) {
            splitPoint = maxChunkSize;
        }

        // 切出第一块
        String chunk = text.substring(0, splitPoint).trim();
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }

        // 准备处理剩余部分
        // 计算下一块的起始位置（考虑 overlap）
        // 下一块的内容应该是：[splitPoint - overlap, length]
        // 但是要注意，不能回退到上一块的开头之前
        int nextStart = Math.max(0, splitPoint - overlap);
        
        // 如果 text 没剩多少了，就不用递归了，直接加进去（由下一次递归处理）
        if (nextStart < text.length()) {
            String remainingText = text.substring(nextStart);
            // 防止死循环：如果剩余文本没有变小，说明切分失败，强制往前移
            if (remainingText.length() >= text.length()) {
                 remainingText = text.substring(splitPoint);
            }
            splitRecursive(remainingText, maxChunkSize, overlap, chunks);
        }
    }
}