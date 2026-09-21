import { Resvg } from '@resvg/resvg-js';
import { writeFileSync } from 'node:fs';

const svg = (b) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="1024" height="1024">
<rect width="512" height="512" fill="#FF0000"/>
${b}
</svg>`;
const W = '#FFFFFF', R = '#FF0000';

// 右角（弯月形）+ 左角 —— 从宽额两端上扬
const RH = `M 342 154 C 382 128 408 104 421 76 C 396 94 376 122 306 152 Z`;
const LH = `M 170 154 C 130 128 104 104 91 76 C 116 94 136 122 206 152 Z`;

// 头（宽阔扁平的额头 + 宽吻，力量型）
const HD = `M 170 150
L 342 150
L 372 195
L 376 250
L 330 300
L 356 348
L 256 360
L 156 348
L 182 300
L 136 250
L 140 195
Z`;

const LOW = `
<rect width="512" height="512" fill="#FF0000"/>
<g transform="translate(81.92,107.76) scale(0.68)">
<path fill="${W}" d="${LH}"/>
<path fill="${W}" d="${RH}"/>
<path fill="${W}" d="${HD}"/>
<path fill="#FF0000" d="M 176 222 Q 228 232 200 244 Q 190 240 176 222 Z"/>
<path fill="#FF0000" d="M 336 222 Q 284 232 312 244 Q 322 240 336 222 Z"/>
<ellipse cx="236" cy="332" rx="9" ry="7" fill="#FF0000"/>
<ellipse cx="276" cy="332" rx="9" ry="7" fill="#FF0000"/>
<path fill="#FF0000" d="M 200 348 Q 256 360 312 348 L 306 356 Q 256 366 206 356 Z"/>
</g>
<circle cx="256" cy="256" r="155" fill="none" stroke="rgba(0,0,0,0.5)" stroke-width="2"/>
`;
writeFileSync('/workspace/QuantApp/tools/bull_curved.png', new Resvg(svg(LOW)).render().asPng());
console.log('done');