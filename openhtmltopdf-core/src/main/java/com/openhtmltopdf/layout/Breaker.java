/*
 * Breaker.java
 * Copyright (c) 2004, 2005 Torbjoern Gannholm,
 * Copyright (c) 2005 Wisconsin Court System
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 */
package com.openhtmltopdf.layout;

import java.util.function.ToIntFunction;

import com.openhtmltopdf.css.constants.CSSName;
import com.openhtmltopdf.css.constants.IdentValue;
import com.openhtmltopdf.css.style.CalculatedStyle;
import com.openhtmltopdf.css.style.CssContext;
import com.openhtmltopdf.extend.FSTextBreaker;
import com.openhtmltopdf.render.FSFont;

/**
 * A utility class that scans the text of a single inline box, looking for the
 * next break point.
 * @author Torbjoern Gannholm
 */
public class Breaker {

    public static void breakFirstLetter(LayoutContext c, LineBreakContext context,
            int avail, CalculatedStyle style) {
        FSFont font = style.getFSFont(c);
        float letterSpacing = style.hasLetterSpacing() ?
                style.getFloatPropertyProportionalWidth(CSSName.LETTER_SPACING, 0, c) :
                0f;
        
        context.setEnd(getFirstLetterEnd(context.getMaster(), context.getStart()));
        context.setWidth(c.getTextRenderer().getWidth(
                c.getFontContext(), font, context.getCalculatedSubstring()) + (int) letterSpacing);

        if (context.getWidth() > avail) {
            context.setNeedsNewLine(true);
            context.setUnbreakable(true);
        }
    }

    private static int getFirstLetterEnd(String text, int start) {
        boolean letterFound = false;
        int end = text.length();
        int currentChar;
        for ( int i = start; i < end; ) {
            currentChar = text.codePointAt(i);
            if (!TextUtil.isFirstLetterSeparatorChar(currentChar)) {
                if (letterFound) {
                    return i;
                } else {
                    letterFound = true;
                }
            }
            i += Character.charCount(currentChar);
        }
        return end;
    }

    public static void breakText(LayoutContext c,
            LineBreakContext context, int avail,
            CalculatedStyle style, boolean tryToBreakAnywhere, int lineWidth) {
        
        FSFont font = style.getFSFont(c);
        IdentValue whitespace = style.getWhitespace();
        float letterSpacing = style.hasLetterSpacing() ? 
                style.getFloatPropertyProportionalWidth(CSSName.LETTER_SPACING, 0, c) : 0f;

        // ====== handle nowrap
        if (whitespace == IdentValue.NOWRAP) {
            context.setEnd(context.getLast());
            context.setWidth(Breaker.getTextWidthWithLetterSpacing(c, font, context.getCalculatedSubstring(), letterSpacing));
            return;
        }

        //check if we should break on the next newline
        if (whitespace == IdentValue.PRE ||
                whitespace == IdentValue.PRE_WRAP ||
                whitespace == IdentValue.PRE_LINE) {
            int n = context.getStartSubstring().indexOf(WhitespaceStripper.EOL);
            if (n > -1) {
                context.setEnd(context.getStart() + n + 1);
                context.setWidth(Breaker.getTextWidthWithLetterSpacing(c, font, context.getCalculatedSubstring(), letterSpacing));
                context.setNeedsNewLine(true);
                context.setEndsOnNL(true);
            } else if (whitespace == IdentValue.PRE) {
            	context.setEnd(context.getLast());
                context.setWidth(Breaker.getTextWidthWithLetterSpacing(c, font, context.getCalculatedSubstring(), letterSpacing));
            }
        }

        //check if we may wrap
        if (whitespace == IdentValue.PRE ||
                (context.isNeedsNewLine() && context.getWidth() <= avail)) {
            return;
        }

        context.setEndsOnNL(false);
        
        if (style.getWordWrap() != IdentValue.BREAK_WORD) {
            // Ordinary old word wrap which will overflow too long unbreakable words.
            doBreakText(c, context, avail, style, tryToBreakAnywhere);
        } else {
            int originalStart = context.getStart();

            // The idea is we only break a word if it will not fit on a line by itself.
            
            while (true) {
                doBreakText(c, context, avail, style, tryToBreakAnywhere);
                
                if (context.isFinished()) {
                    break;
                } else if (tryToBreakAnywhere && context.isEndsOnWordBreak()) {
                    // We were in char breaking mode, but have found a line breaking opportunity.
                    tryToBreakAnywhere = false;
                } else if (!tryToBreakAnywhere && context.isNeedsNewLine() && context.getNextWidth() >= lineWidth) {
                    // The next word will not fit on a line by itself so turn on char breaking mode.
                    tryToBreakAnywhere = true;
                } else if (!tryToBreakAnywhere && context.isUnbreakable()) {
                    // Safety valve: Not sure we need it.
                    break;
                } else if (context.isNeedsNewLine()) {
                    // Stop, we're at the end of the line.
                    break;
                }
                
                avail -= context.getWidth();
                context.setStart(context.getEnd());
            }

            context.setStart(originalStart);
            
            // We need to know this for the next line.
            context.setFinishedInCharBreakingMode(tryToBreakAnywhere);
        }
    }
    
    private static void doBreakText(LayoutContext c,
            LineBreakContext context, int avail, CalculatedStyle style,
            boolean tryToBreakAnywhere) {
        if (!tryToBreakAnywhere) {
            doBreakText(c, context, avail, style, STANDARD_LINE_BREAKER);
        } else {
            FSFont font = style.getFSFont(c);

            float letterSpacing = style.hasLetterSpacing()
                    ? style.getFloatPropertyProportionalWidth(CSSName.LETTER_SPACING, 0, c)
                    : 0f;

            ToIntFunction<String> measurer = (str) ->
                   c.getTextRenderer().getWidth(c.getFontContext(), font, str);
                  
            String currentString = context.getStartSubstring();
            FSTextBreaker lineIterator = STANDARD_LINE_BREAKER.getBreaker(currentString, c.getSharedContext());
            FSTextBreaker charIterator = STANDARD_CHARACTER_BREAKER.getBreaker(currentString, c.getSharedContext());       
                   
            doBreakCharacters(currentString, lineIterator, charIterator, context, avail, letterSpacing, measurer);
        }
    }
    
    /**
     * Breaks at most one word (until the next word break) going character by character to see
     * what will fit in.
     */
    static void doBreakCharacters(
            String currentString,
            FSTextBreaker lineIterator,
            FSTextBreaker charIterator,
            LineBreakContext context,
            int avail,
            float letterSpacing,
            ToIntFunction<String> measurer) {

        // The next word break opportunity. We don't want to go past this
        // because we want to resume (if possible) normal word breaking after this
        // too long word has been broken anywhere to fit.
        int nextWordBreak = lineIterator.next();
        if (nextWordBreak == 0) {
            nextWordBreak = lineIterator.next();
        }
        if (nextWordBreak < 0) {
            // No word breaking opportunity, use end of the string.
            nextWordBreak = currentString.length();
        }
        
        // Next character break opportunity. Working variable.
        int nextCharBreak = charIterator.next();
        if (nextCharBreak < 0) {
            nextCharBreak = nextWordBreak;
        }
        
        // Working vars for current graphics length which may go over the 
        // available length.
        int graphicsLength = 0;
        
        // Working var to denote the first position, after which we are looking
        // for break opportunities. This will change as we go past each break
        // opportunity that fits.
        int left = 0;
        
        // Maintain a record of the last good wrap index and the last good graphics length.
        int lastGoodWrap = 0;
        int lastGoodGraphicsLength = 0;
        
        // While we've found a another break opportunity and its in our range (ie. before
        // the next word break) and fits keep going.
        while (nextCharBreak >= 0 &&
               nextCharBreak <= nextWordBreak &&
               graphicsLength < avail) {
            String subString = currentString.substring(left, nextCharBreak);
            float extraSpacing = (nextCharBreak - left) * letterSpacing;
            
            int splitWidth = (int) (measurer.applyAsInt(subString) + extraSpacing);
            
            lastGoodWrap = left;
            left = nextCharBreak;
            
            lastGoodGraphicsLength = graphicsLength;
            
            graphicsLength += splitWidth;
            nextCharBreak = charIterator.next();
        }
        
        if (graphicsLength == avail) {
            // Exact fit..
            context.setNeedsNewLine(currentString.length() > left);
            context.setEnd(left);
            context.setWidth(graphicsLength);
            return;
        }

        if (nextCharBreak < 0) {
            nextCharBreak = nextWordBreak;
        }
               
        if (graphicsLength < avail) {
            // Try for the last bit too!
            lastGoodWrap = nextCharBreak;
            lastGoodGraphicsLength = graphicsLength;
            
            nextCharBreak = Math.min(currentString.length(), nextWordBreak);
            
            float extraSpacing = (nextCharBreak - left) * letterSpacing;
            int splitWidth = (int) (measurer.applyAsInt(currentString.substring(left, nextCharBreak)) + extraSpacing);

            graphicsLength += splitWidth;
        }

        if (graphicsLength <= avail) {
            // The entire word fit.
            context.setWidth(graphicsLength);
            context.setEnd(nextCharBreak + context.getStart());
            context.setEndsOnWordBreak(nextCharBreak == nextWordBreak);
            return;
        }
        
        // We need a newline for this word.
        context.setNeedsNewLine(true);
        
        if (lastGoodWrap != 0) {
            // We found a wrap point in which to wrap this word.
            context.setWidth(lastGoodGraphicsLength);
            context.setEnd(lastGoodWrap + context.getStart());
            context.setEndsOnWordBreak(lastGoodWrap == nextWordBreak);
            return;
        } else {
            // One character word, so we didn't find a wrap point.
            float extraSpacing = nextWordBreak * letterSpacing;
            int splitWidth = (int) (measurer.applyAsInt(currentString.substring(0, nextWordBreak)) + extraSpacing); 

            context.setUnbreakable(true);
            context.setEnd(nextWordBreak + context.getStart());
            context.setEndsOnWordBreak(true);
            context.setWidth(splitWidth);
            return;
        }
    }
    
    public static final char SOFT_HYPHEN = '\u00ad';
    
    private static class AppBreakOpportunity {
        int left;
        int right;
        int graphicsLength;
        int withHyphenGraphicsLength;
        boolean isSoftHyphenBreak;
        
        void copyTo(AppBreakOpportunity other) {
            other.left = left;
            other.right = right;
            other.graphicsLength = graphicsLength;
            other.withHyphenGraphicsLength = withHyphenGraphicsLength;
            other.isSoftHyphenBreak = isSoftHyphenBreak;
        }
    }
    
    public static void doBreakText(
            LayoutContext c,
            LineBreakContext context,
            int avail,
            CalculatedStyle style,
            TextBreakerSupplier lineBreaker) {

        FSFont font = style.getFSFont(c);

        float letterSpacing = style.hasLetterSpacing()
                ? style.getFloatPropertyProportionalWidth(CSSName.LETTER_SPACING, 0, c)
                : 0f;

        String currentString = context.getStartSubstring();
        FSTextBreaker iterator = lineBreaker.getBreaker(currentString, c.getSharedContext());

        int lastWrap = 0;
        
        AppBreakOpportunity current = new AppBreakOpportunity();
        AppBreakOpportunity prev = new AppBreakOpportunity();
        
        current.right = iterator.next();
        if (current.right == 0) {
            current.right = iterator.next();
        }

        int nextUnfittableSplitWidth = 0;
        
        while (current.right > 0 && current.graphicsLength <= avail) {
            current.copyTo(prev);
            
            String subString = currentString.substring(current.left, current.right);
            float extraSpacing = (current.right - current.left) * letterSpacing;
            
            int normalSplitWidth = (int) (c.getTextRenderer().getWidth(
                    c.getFontContext(), font, subString) + extraSpacing);
            
            if (currentString.charAt(current.right - 1) == SOFT_HYPHEN) {
                current.isSoftHyphenBreak = true;
                int withTrailingHyphenSplitWidth = (int) (c.getTextRenderer().getWidth(
                        c.getFontContext(), font, subString + '-') + 
                        extraSpacing + letterSpacing);
                current.withHyphenGraphicsLength = current.graphicsLength + withTrailingHyphenSplitWidth;
                
                if (current.withHyphenGraphicsLength > avail) {
                    current.graphicsLength = current.withHyphenGraphicsLength;
                    lastWrap = current.left;
                    current.left = current.right;
                    current.right = iterator.next();
                    break;
                }
            } else {
                current.isSoftHyphenBreak = false;
                current.withHyphenGraphicsLength += normalSplitWidth;
            }
            
            current.graphicsLength += normalSplitWidth;
            nextUnfittableSplitWidth = normalSplitWidth;
            lastWrap = current.left;
            current.left = current.right;
            current.right = iterator.next();
        }
        
        if (current.graphicsLength <= avail) {
            // Try for the last bit too!
            lastWrap = current.left;
            current.copyTo(prev);
            current.right = currentString.length();
            float extraSpacing = (current.right - current.left) * letterSpacing;
            int splitWidth = (int) (c.getTextRenderer().getWidth(
                    c.getFontContext(), font, currentString.substring(current.left)) + extraSpacing);
            current.graphicsLength += splitWidth;
            nextUnfittableSplitWidth = splitWidth;
        }

        if (current.graphicsLength <= avail) {
            context.setWidth(current.graphicsLength);
            context.setEnd(context.getMaster().length());
            // It all fit!
            return;
        }

        context.setNeedsNewLine(true);

        if (lastWrap != 0) {
            // Found a place to wrap
            if (prev.isSoftHyphenBreak) {
                context.setEndsOnSoftHyphen(true);
                context.setWidth(prev.withHyphenGraphicsLength);
            } else {
                context.setWidth(prev.graphicsLength);
            }
            
            context.setNextWidth(nextUnfittableSplitWidth);
            context.setEnd(context.getStart() + lastWrap);
        } else {
            // Unbreakable string
            if (current.left == 0) {
                current.left = currentString.length();
            }

            context.setEnd(context.getStart() + current.left);
            context.setUnbreakable(true);

            if (current.left == currentString.length()) {
                String text = context.getCalculatedSubstring();
                float extraSpacing = text.length() * letterSpacing;
                context.setWidth((int) (c.getTextRenderer().getWidth(
                        c.getFontContext(), font, text) + extraSpacing));
            } else {
                context.setWidth(current.graphicsLength);
            }
        }
    }
    
    public interface TextBreakerSupplier {
    	public FSTextBreaker getBreaker(String str, SharedContext sharedContext);
    }
    
    private static class CharacterBreakerSupplier implements TextBreakerSupplier {
		@Override
		public FSTextBreaker getBreaker(String str, SharedContext sharedContext) {
			return getCharacterBreakStream(str, sharedContext);
		}
    }

    private static class LineBreakerSupplier implements TextBreakerSupplier {
		@Override
		public FSTextBreaker getBreaker(String str, SharedContext sharedContext) {
			return getLineBreakStream(str, sharedContext);
		}
    }
    
    public static final TextBreakerSupplier STANDARD_CHARACTER_BREAKER = new CharacterBreakerSupplier();
    public static final TextBreakerSupplier STANDARD_LINE_BREAKER = new LineBreakerSupplier();
    
	public static FSTextBreaker getCharacterBreakStream(String currentString, SharedContext sharedContext) {
		FSTextBreaker i = sharedContext.getCharacterBreaker();
		i.setText(currentString);
		return i;
	}

	public static FSTextBreaker getLineBreakStream(String s, SharedContext shared) {
		FSTextBreaker i = shared.getLineBreaker();
		i.setText(s);
		return i;
	}

	/**
	 * Gets the width of a string with letter spacing factored in.
	 * Favor this method over using the text renderer directly.
	 */
    public static int getTextWidthWithLetterSpacing(CssContext c, FSFont font, String text, float letterSpacing) {
        float extraSpace = text.length() * letterSpacing;
        return (int) (c.getTextRenderer().getWidth(c.getFontContext(), font, text) + extraSpace);
    }
}
