# Coverage staples list (source for the common-products catalogue)

Sources, supplied by Semion on 2026-10-20:
1. Community Insights NZ, "Local Grocery Shopping List" (27/05/2025) — weekly/monthly household list.
2. PAK'nSAVE, "Pantry staples and shopping list" — https://www.paknsave.co.nz/planning-shop/shopping-tips/pantry-staples-and-shopping-list
3. ACC "Example of a weekly shopping list" — NOT read: the ACC site returned a bot-protection page, so it was not used. Add its items by hand if wanted.

Food items only (toiletries and cleaning products from source 1 are out of scope for the price engine).

## Community Insights (weekly / monthly)
Cornflakes, rice snaps, rolled oats, wheat biscuits (Weet-Bix); cheese (colby), milk 2L, yoghurt; fresh fish, frozen fish (hoki), tinned tuna; apples, bananas, pears; margarine/spread, baked beans, canned chickpeas, dried pasta (spaghetti), eggs (size 7, 12 pack), luncheon meat, instant noodles, canned spaghetti, creamed sweetcorn, canned tomatoes, white rice 1kg, white sandwich bread, toast bread; beef mince, chicken drumsticks, sausages; baking powder, chicken stock powder, cocoa, curry powder, malt drink powder (Milo), crushed garlic, honey, icing sugar, instant coffee, jam, mixed herbs, peanut butter, pepper, salt, soy sauce, sugar (raw), tea bags, tomato sauce, vegetable oil, white flour; cabbage, carrots, frozen mixed vegetables, onions, potatoes, pumpkin.

## PAK'nSAVE pantry staples
Bread, milk, eggs, cooking oils, butter, tinned tomatoes, potatoes, dried pasta, rice, flour; salt, black pepper, olive oil (extra-virgin), canola/peanut/vegetable/rice bran oil, vinegars (apple cider, white, red wine, white wine, balsamic, rice, sherry); garlic powder, stock powders (chicken, beef, vegetable), basil, oregano, thyme, cumin, smoked paprika, cayenne, cinnamon, ground coriander, ground ginger, chilli powder/flakes, curry powder, bay leaf, garam masala, nutmeg, rosemary; noodles, breadcrumbs, tea, coffee, almonds, peanuts, pine nuts, walnuts, cashews, crackers, pretzels, chips, popcorn, cereals, raisins, apricots, dates, rolled oats, gravy mix, bulgur, quinoa, polenta, couscous; baked beans, kidney beans, chickpeas, cannellini beans, black beans, lentils, tuna, salmon, sardines, tomato paste, diced/crushed/pureed tomatoes, passata, corn, green beans, beetroot, corned beef, creamed rice, reduced cream, condensed milk, canned peaches/apples/pears/pineapple, coconut milk, soup, canned spaghetti, olives, capers; tomato sauce, aioli, soy sauce, mustard, worcestershire sauce, mayonnaise, hoisin, peanut butter, jam, hot sauce, tabasco, sriracha, sambal, fish sauce, pickles, honey, curry paste, barbecue sauce, maple syrup, sweet chilli sauce, red wine, white wine, apple sauce, sesame oil, crushed garlic, lemon/lime juice, salad dressing; flour (plain, self-raising, high-grade, gluten-free), baking soda, baking powder, yeast, cream of tartar, vanilla extract, brown/white/caster/icing sugar, cocoa powder, chocolate, evaporated milk, long-life milk, milk powder, long-life cream, cornflour, cornmeal, gelatine; parmesan, edam, colby, tasty cheese, butter, plain yoghurt, sour cream, hummus; bananas, apples, avocados, broccoli, cauliflower, lemons, carrots, courgette, celery, lettuce, mixed greens, spinach, kale, onions, garlic, ginger, potatoes, kumara, tomatoes, capsicum, mushrooms, spring onions; frozen beef mince, sausages, chicken breast, prawns, bacon, peas, mixed vegetables, corn, berries, peaches, mango, pastry, pizza bases, vanilla ice cream.


## Catalogue status (2026-10-20)

Every food item above now resolves to a canonical key in `services/pricing/src/main/resources/common-products.txt` (aliases and exclude words), and each key has a line in `category-map.txt`. `CanonicalStaplesTest` (fixture `canonical-staples.txt`, realistic store names) fails if a staple loses its key or lands on the wrong one. Where the list names several items (for example "vinegars" or "dried spices") they share a key unless their prices should not be compared (apple cider and balsamic vinegar, crushed garlic, ground ginger, raw sugar and so on have their own). Paths come only from `category-index.txt` (the chains' own sitemaps); none were invented and no request was made.

New keys: spaghetti-canned, sausages-pork, sugar-raw, garlic-crushed, garlic-powder, ginger-ground, vinegar-apple-cider, vinegar-balsamic, pretzels, dried-apricots, gravy, grains-other (quinoa, couscous, bulgur, polenta, cornmeal), salmon-canned, beetroot-canned, corned-beef, creamed-rice, cream-reduced, cream-uht, fruit-canned, olives, capers, worcestershire, asian-sauces (hoisin, oyster, fish, teriyaki), hot-sauce, pickles, curry-paste, apple-sauce, lemon-juice, salad-dressing, flour-gluten-free, yeast, cream-of-tartar, gelatine, kale, corn-frozen, fruit-frozen, pastry-frozen, pizza-base. Extra aliases went onto cereal (rice snaps, corn flakes), weetbix (wheat biscuits), milo (malt drink), mixed-nuts (pine nuts), dried-herbs (rosemary, bay leaves, ground coriander), lettuce (mixed greens), tomatoes-canned (passata), noodles and instant-ramen (instant noodle cups stay noodles, not soup).

## Needs category URL

Products whose `category-map.txt` line has `-` for a chain: no verified category page for that chain is in `category-index.txt` yet. The runtime sitemap discovery is expected to fill them. `F` = New World and PAK'nSAVE, `W` = Woolworths. Keys listed with only `W` already have a New World / PAK'nSAVE page.

| Key | Staple | Missing |
|---|---|---|
| `creamed-rice` | creamed rice | F and W |
| `lemon-juice` | lemon / lime juice | F and W |
| `sausages-pork` | sausages (pork) | W |
| `garlic-crushed` | crushed garlic | W |
| `gravy` | gravy mix | W |
| `grains-other` | bulgur, quinoa, polenta, couscous, cornmeal | W |
| `corned-beef` | corned beef | W |
| `fruit-canned` | canned peaches / apples / pears / pineapple | W |
| `olives` | olives | W |
| `capers` | capers | W |
| `worcestershire` | worcestershire sauce | W |
| `pickles` | pickles | W |
| `curry-paste` | curry paste | W |
| `apple-sauce` | apple sauce | W |
| `salad-dressing` | salad dressing | W |
| `flour-gluten-free` | gluten-free flour | W |
| `fruit-frozen` | frozen berries, peaches, mango | W |
| `pastry-frozen` | pastry | W |
| `pizza-base` | pizza bases | W |
| `syrup` | maple syrup | W |
| `milo` | malt drink powder (Milo) | W |
| `kimchi` | (not a listed staple) | W |
| `miso` | (not a listed staple) | W |

Note: `category-index.txt` holds only 137 Woolworths paths (the partial list), so most `W` gaps are pages that very likely exist but are not yet in the index.
