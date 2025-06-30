// @ts-check
const canvas = document.querySelector('canvas');
const ctx = canvas.getContext('2d');
const textInput = document.querySelector('textarea');
const defaultText = textInput.value;
let inverted = false;
let bgColor = 'white';
let textColor = 'black';

const commonInstance = new NfcEIWCommon(canvas);
const clearCanvas = commonInstance.clearCanvas.bind(commonInstance);

/**
 * A helper function to take raw text and break it into lines
 * that will fit on the canvas. It handles both existing newlines
 * and wraps long lines.
 * @param {CanvasRenderingContext2D} context The canvas context.
 * @param {string} text The full text.
 * @param {number} maxWidth The maximum width a line can be.
 * @returns {string[]} An array of strings, where each string is a line to be drawn.
 */
function getLines(context, text, maxWidth) {
    const finalLines = [];
    // First, split the text by any existing newlines.
    // This regex handles both \n and \r\n
    const paragraphs = text.split(/\r?\n/);

    for (const paragraph of paragraphs) {
        const words = paragraph.split(' ');
        let currentLine = words[0];

        for (let i = 1; i < words.length; i++) {
            const word = words[i];
            const width = context.measureText(currentLine + " " + word).width;
            if (width < maxWidth) {
                currentLine += " " + word;
            } else {
                finalLines.push(currentLine);
                currentLine = word;
            }
        }
        finalLines.push(currentLine);
    }
    return finalLines;
}


/**
 * Normal operation is black text on white, but you can set inverted
 * @param {boolean} [updatedInverted]
 */
function setInverted(updatedInverted) {
	inverted = typeof updatedInverted === 'boolean' ? updatedInverted : !inverted;
	if (inverted) {
		bgColor = 'black';
		textColor = 'white';
	} else {
		bgColor = 'white';
		textColor = 'black';
	}
	renderToCanvas();
}

function drawBg() {
	ctx.fillStyle = bgColor;
	ctx.fillRect(0, 0, canvas.width, canvas.height);
}

function renderToCanvas() {
	clearCanvas();
	drawBg();

    // --- New Drawing Logic ---
    const text = textInput.value;
    if (!text) return;

    const fontSize = 20;
    const padding = 10;
    const lineHeight = fontSize * 1.2;
    ctx.font = `${fontSize}px Arial`;
    ctx.fillStyle = textColor;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';

    const lines = getLines(ctx, text, canvas.width - (padding * 2));
    let y = padding;
    for (const line of lines) {
        ctx.fillText(line, padding, y);
        y += lineHeight;
    }
}

renderToCanvas();
setTimeout(renderToCanvas, 200);

// Attach listeners
textInput.addEventListener('keyup', renderToCanvas);
textInput.addEventListener('input', renderToCanvas);
document.querySelector('button#addLineBreak').addEventListener('click', () => {
	textInput.value += '\n';
    renderToCanvas();
});
document.querySelector('button#reset').addEventListener('click', () => {
	textInput.value = defaultText;
	setInverted(false);
	renderToCanvas();
});
document.querySelector('button#setInverted').addEventListener('click', () => {
	setInverted();
});