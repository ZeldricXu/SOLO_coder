import pygame
import asyncio
import websockets
import json
from collections import deque

TILE_SIZE = 24
WALL = (50, 50, 50)
FLOOR = (100, 100, 100)
CORRIDOR = (90, 90, 90)
DOOR = (139, 69, 19)
STAIRS = (255, 215, 0)
TRAP = (255, 0, 0)
CHEST = (255, 165, 0)
ALTAR = (128, 0, 128)
WATER = (0, 100, 200)
FOG = (20, 20, 20)

PLAYER = (0, 255, 0)
MONSTER = (255, 0, 0)
NPC = (255, 255, 0)
ITEM = (0, 255, 255)

WHITE = (255, 255, 255)
BLUE = (0, 150, 255)
PURPLE = (180, 0, 255)
GOLD = (255, 215, 0)
ORANGE = (255, 140, 0)

TILE_COLORS = {
    'wall': WALL,
    'floor': FLOOR,
    'corridor': CORRIDOR,
    'door': DOOR,
    'stairs': STAIRS,
    'trap': TRAP,
    'chest': CHEST,
    'altar': ALTAR,
    'water': WATER,
}

RARITY_COLORS = {
    'common': WHITE,
    'uncommon': BLUE,
    'rare': PURPLE,
    'epic': GOLD,
    'legendary': ORANGE,
}

CLASSES = [
    {'name': 'Warrior', 'hp': 150, 'mana': 30, 'attack': 15, 'defense': 12, 'speed': 8, 'color': (200, 50, 50)},
    {'name': 'Mage', 'hp': 80, 'mana': 150, 'attack': 20, 'defense': 5, 'speed': 10, 'color': (50, 50, 200)},
    {'name': 'Rogue', 'hp': 100, 'mana': 60, 'attack': 18, 'defense': 7, 'speed': 15, 'color': (50, 200, 50)},
    {'name': 'Priest', 'hp': 90, 'mana': 120, 'attack': 10, 'defense': 8, 'speed': 9, 'color': (200, 200, 50)},
]

class GameClient:
    def __init__(self):
        pygame.init()
        self.screen = pygame.display.set_mode((1200, 800))
        pygame.display.set_caption('Roguelike Dungeon')
        self.clock = pygame.time.Clock()
        self.font = pygame.font.SysFont('Arial', 14)
        self.large_font = pygame.font.SysFont('Arial', 24)
        self.title_font = pygame.font.SysFont('Arial', 48)
        
        self.websocket = None
        self.game_state = {}
        self.ui_state = 'menu'
        self.input_state = {
            'selected_slot': 0,
            'target_mode': False,
            'skill_targeting': None,
            'hovered_entity': None,
            'chat_input': '',
            'username': '',
            'password': '',
            'login_mode': 'login',
            'selected_class': 0,
        }
        self.message_queue = deque()
        self.combat_log = deque(maxlen=5)
        self.chat_messages = deque(maxlen=50)
        self.running = True
        
    async def connect(self, host, port):
        self.websocket = await websockets.connect(f'ws://{host}:{port}')
        
    async def send_message(self, msg_type, data):
        if self.websocket:
            message = json.dumps({'type': msg_type, 'data': data})
            await self.websocket.send(message)
            
    async def receive_loop(self):
        try:
            async for message in self.websocket:
                self.message_queue.append(json.loads(message))
        except websockets.exceptions.ConnectionClosed:
            self.running = False
            
    def process_messages(self):
        while self.message_queue:
            msg = self.message_queue.popleft()
            msg_type = msg.get('type')
            data = msg.get('data', {})
            
            if msg_type == 'game_state':
                self.game_state = data
            elif msg_type == 'combat_log':
                self.combat_log.append(data.get('message', ''))
            elif msg_type == 'chat_message':
                self.chat_messages.append(data)
            elif msg_type == 'login_success':
                self.ui_state = 'class_select'
            elif msg_type == 'login_failed':
                self.combat_log.append(f"Login failed: {data.get('reason', '')}")
            elif msg_type == 'game_over':
                self.ui_state = 'game_over'
            elif msg_type == 'inventory_update':
                self.game_state['inventory'] = data.get('inventory', [])
                
    def render_map(self):
        map_data = self.game_state.get('map', {})
        tiles = map_data.get('tiles', [])
        visible = map_data.get('visible', [])
        remembered = map_data.get('remembered', [])
        
        offset_x = 10
        offset_y = 10
        
        for y, row in enumerate(tiles):
            for x, tile in enumerate(row):
                rect = pygame.Rect(offset_x + x * TILE_SIZE, offset_y + y * TILE_SIZE, TILE_SIZE, TILE_SIZE)
                
                is_visible = visible[y][x] if y < len(visible) and x < len(visible[y]) else False
                is_remembered = remembered[y][x] if y < len(remembered) and x < len(remembered[y]) else False
                
                if is_visible:
                    color = TILE_COLORS.get(tile, FLOOR)
                    pygame.draw.rect(self.screen, color, rect)
                    pygame.draw.rect(self.screen, (30, 30, 30), rect, 1)
                elif is_remembered:
                    pygame.draw.rect(self.screen, FOG, rect)
                    
    def render_entities(self):
        map_data = self.game_state.get('map', {})
        visible = map_data.get('visible', [])
        entities = self.game_state.get('entities', [])
        
        offset_x = 10
        offset_y = 10
        
        self.input_state['hovered_entity'] = None
        mouse_pos = pygame.mouse.get_pos()
        
        for entity in entities:
            x = entity.get('x', 0)
            y = entity.get('y', 0)
            
            if y >= len(visible) or x >= len(visible[y]) or not visible[y][x]:
                continue
                
            rect = pygame.Rect(offset_x + x * TILE_SIZE, offset_y + y * TILE_SIZE, TILE_SIZE, TILE_SIZE)
            
            entity_type = entity.get('type', '')
            if entity_type == 'player':
                color = PLAYER
            elif entity_type == 'monster':
                color = MONSTER
            elif entity_type == 'npc':
                color = NPC
            elif entity_type == 'item':
                color = ITEM
            else:
                color = WHITE
                
            pygame.draw.rect(self.screen, color, rect.inflate(-4, -4))
            
            if rect.collidepoint(mouse_pos):
                self.input_state['hovered_entity'] = entity
                pygame.draw.rect(self.screen, WHITE, rect, 2)
                
    def render_entity_tooltip(self):
        entity = self.input_state.get('hovered_entity')
        if not entity:
            return
            
        mouse_pos = pygame.mouse.get_pos()
        tooltip_x = mouse_pos[0] + 15
        tooltip_y = mouse_pos[1] + 15
        
        name = entity.get('name', 'Unknown')
        lines = [name]
        
        if 'hp' in entity:
            lines.append(f"HP: {entity['hp']}/{entity.get('max_hp', entity['hp'])}")
        if 'attack' in entity:
            lines.append(f"ATK: {entity['attack']}")
        if 'defense' in entity:
            lines.append(f"DEF: {entity['defense']}")
        if 'rarity' in entity:
            lines.append(f"Rarity: {entity['rarity']}")
            
        max_width = max(self.font.size(line)[0] for line in lines)
        tooltip_height = len(lines) * 20 + 10
        
        pygame.draw.rect(self.screen, (0, 0, 0), (tooltip_x, tooltip_y, max_width + 20, tooltip_height))
        pygame.draw.rect(self.screen, WHITE, (tooltip_x, tooltip_y, max_width + 20, tooltip_height), 1)
        
        for i, line in enumerate(lines):
            text = self.font.render(line, True, WHITE)
            self.screen.blit(text, (tooltip_x + 10, tooltip_y + 5 + i * 20))
            
    def render_hud(self):
        player = self.game_state.get('player', {})
        hud_x = 800
        hud_y = 10
        
        pygame.draw.rect(self.screen, (30, 30, 30), (hud_x, hud_y, 380, 200))
        
        name = player.get('name', 'Player')
        class_name = player.get('class', 'Warrior')
        text = self.large_font.render(f"{name} ({class_name})", True, WHITE)
        self.screen.blit(text, (hud_x + 10, hud_y + 10))
        
        hp = player.get('hp', 100)
        max_hp = player.get('max_hp', 100)
        hp_ratio = hp / max_hp if max_hp > 0 else 0
        pygame.draw.rect(self.screen, (100, 0, 0), (hud_x + 10, hud_y + 50, 200, 20))
        pygame.draw.rect(self.screen, (255, 0, 0), (hud_x + 10, hud_y + 50, 200 * hp_ratio, 20))
        text = self.font.render(f"HP: {hp}/{max_hp}", True, WHITE)
        self.screen.blit(text, (hud_x + 220, hud_y + 50))
        
        mana = player.get('mana', 50)
        max_mana = player.get('max_mana', 50)
        mana_ratio = mana / max_mana if max_mana > 0 else 0
        pygame.draw.rect(self.screen, (0, 0, 100), (hud_x + 10, hud_y + 80, 200, 20))
        pygame.draw.rect(self.screen, (0, 100, 255), (hud_x + 10, hud_y + 80, 200 * mana_ratio, 20))
        text = self.font.render(f"MP: {mana}/{max_mana}", True, WHITE)
        self.screen.blit(text, (hud_x + 220, hud_y + 80))
        
        attack = player.get('attack', 10)
        defense = player.get('defense', 5)
        speed = player.get('speed', 10)
        gold = player.get('gold', 0)
        floor = self.game_state.get('floor', 1)
        turn = self.game_state.get('turn', 0)
        
        text = self.font.render(f"ATK: {attack}  DEF: {defense}  SPD: {speed}", True, WHITE)
        self.screen.blit(text, (hud_x + 10, hud_y + 110))
        text = self.font.render(f"Gold: {gold}", True, GOLD)
        self.screen.blit(text, (hud_x + 10, hud_y + 135))
        text = self.font.render(f"Floor: {floor}  Turn: {turn}", True, WHITE)
        self.screen.blit(text, (hud_x + 10, hud_y + 160))
        
        skills = player.get('skills', [])
        for i, skill in enumerate(skills[:4]):
            skill_x = hud_x + 10 + i * 90
            skill_y = hud_y + 185
            pygame.draw.rect(self.screen, (50, 50, 50), (skill_x, skill_y, 80, 40))
            skill_name = skill.get('name', f'Skill {i+1}')
            text = self.font.render(f"{i+1}. {skill_name}", True, WHITE)
            self.screen.blit(text, (skill_x + 5, skill_y + 5))
            cost = skill.get('cost', 0)
            text = self.font.render(f"MP: {cost}", True, BLUE)
            self.screen.blit(text, (skill_x + 5, skill_y + 20))
            
    def render_inventory(self):
        inv_x = 200
        inv_y = 100
        slot_size = 50
        cols = 5
        rows = 4
        
        pygame.draw.rect(self.screen, (0, 0, 0, 200), (0, 0, 1200, 800))
        pygame.draw.rect(self.screen, (40, 40, 40), (inv_x - 10, inv_y - 10, cols * slot_size + 20, rows * slot_size + 20))
        
        inventory = self.game_state.get('inventory', [])
        mouse_pos = pygame.mouse.get_pos()
        hovered_item = None
        
        for i in range(20):
            col = i % cols
            row = i // cols
            slot_x = inv_x + col * slot_size
            slot_y = inv_y + row * slot_size
            
            pygame.draw.rect(self.screen, (60, 60, 60), (slot_x, slot_y, slot_size - 2, slot_size - 2))
            
            if i < len(inventory):
                item = inventory[i]
                rarity = item.get('rarity', 'common')
                color = RARITY_COLORS.get(rarity, WHITE)
                pygame.draw.rect(self.screen, color, (slot_x + 5, slot_y + 5, slot_size - 12, slot_size - 12))
                
                if self.input_state['selected_slot'] == i:
                    pygame.draw.rect(self.screen, WHITE, (slot_x, slot_y, slot_size - 2, slot_size - 2), 2)
                    
                slot_rect = pygame.Rect(slot_x, slot_y, slot_size - 2, slot_size - 2)
                if slot_rect.collidepoint(mouse_pos):
                    hovered_item = item
                    
        if hovered_item:
            tip_x = mouse_pos[0] + 15
            tip_y = mouse_pos[1] + 15
            lines = [
                hovered_item.get('name', 'Item'),
                f"Rarity: {hovered_item.get('rarity', 'common')}",
                f"Type: {hovered_item.get('type', 'misc')}",
            ]
            if 'attack' in hovered_item:
                lines.append(f"+{hovered_item['attack']} ATK")
            if 'defense' in hovered_item:
                lines.append(f"+{hovered_item['defense']} DEF")
                
            max_w = max(self.font.size(l)[0] for l in lines)
            pygame.draw.rect(self.screen, (0, 0, 0), (tip_x, tip_y, max_w + 20, len(lines) * 20 + 10))
            for i, line in enumerate(lines):
                text = self.font.render(line, True, RARITY_COLORS.get(hovered_item.get('rarity', 'common'), WHITE))
                self.screen.blit(text, (tip_x + 10, tip_y + 5 + i * 20))
                
        text = self.font.render("Press I to close inventory", True, WHITE)
        self.screen.blit(text, (inv_x, inv_y + rows * slot_size + 10))
        
    def render_combat_log(self):
        log_y = 700
        pygame.draw.rect(self.screen, (30, 30, 30), (10, log_y, 780, 90))
        
        for i, msg in enumerate(reversed(list(self.combat_log))):
            text = self.font.render(msg, True, WHITE)
            self.screen.blit(text, (20, log_y + 10 + i * 18))
            
    def render_chat(self):
        chat_x = 800
        chat_y = 400
        
        pygame.draw.rect(self.screen, (30, 30, 30), (chat_x, chat_y, 380, 290))
        
        msg_y = chat_y + 10
        for msg in reversed(list(self.chat_messages)[-12:]):
            text = self.font.render(f"{msg.get('user', '')}: {msg.get('text', '')}", True, WHITE)
            self.screen.blit(text, (chat_x + 10, msg_y))
            msg_y += 18
            
        pygame.draw.rect(self.screen, (50, 50, 50), (chat_x + 10, chat_y + 250, 360, 30))
        text = self.font.render(self.input_state['chat_input'] + '_', True, WHITE)
        self.screen.blit(text, (chat_x + 15, chat_y + 255))
        
    def render_menu(self):
        self.screen.fill((20, 20, 40))
        
        title = self.title_font.render('ROGUELIKE DUNGEON', True, GOLD)
        self.screen.blit(title, (350, 100))
        
        menu_y = 250
        options = [
            ('Login/Register', 1),
            ('Start Game', 2),
            ('Leaderboard', 3),
        ]
        
        mouse_pos = pygame.mouse.get_pos()
        
        for i, (text, _) in enumerate(options):
            btn_rect = pygame.Rect(450, menu_y + i * 60, 300, 50)
            color = (80, 80, 100)
            if btn_rect.collidepoint(mouse_pos):
                color = (100, 100, 150)
            pygame.draw.rect(self.screen, color, btn_rect)
            pygame.draw.rect(self.screen, GOLD, btn_rect, 2)
            
            text_surf = self.large_font.render(text, True, WHITE)
            self.screen.blit(text_surf, (btn_rect.x + 80, btn_rect.y + 10))
            
        if self.input_state['login_mode'] in ['login', 'register']:
            pygame.draw.rect(self.screen, (40, 40, 60), (350, 450, 500, 200))
            
            mode_text = 'LOGIN' if self.input_state['login_mode'] == 'login' else 'REGISTER'
            text = self.large_font.render(mode_text, True, GOLD)
            self.screen.blit(text, (550, 460))
            
            text = self.font.render('Username:', True, WHITE)
            self.screen.blit(text, (370, 510))
            pygame.draw.rect(self.screen, (60, 60, 80), (470, 505, 360, 30))
            text = self.font.render(self.input_state['username'] + '_', True, WHITE)
            self.screen.blit(text, (475, 510))
            
            text = self.font.render('Password:', True, WHITE)
            self.screen.blit(text, (370, 550))
            pygame.draw.rect(self.screen, (60, 60, 80), (470, 545, 360, 30))
            text = self.font.render('*' * len(self.input_state['password']) + '_', True, WHITE)
            self.screen.blit(text, (475, 550))
            
            btn_rect = pygame.Rect(450, 590, 120, 40)
            pygame.draw.rect(self.screen, (0, 150, 0), btn_rect)
            text = self.font.render('Submit', True, WHITE)
            self.screen.blit(text, (btn_rect.x + 35, btn_rect.y + 10))
            
            btn_rect = pygame.Rect(600, 590, 120, 40)
            pygame.draw.rect(self.screen, (150, 0, 0), btn_rect)
            text = self.font.render('Cancel', True, WHITE)
            self.screen.blit(text, (btn_rect.x + 35, btn_rect.y + 10))
            
    def render_class_select(self):
        self.screen.fill((20, 20, 40))
        
        title = self.title_font.render('SELECT YOUR CLASS', True, GOLD)
        self.screen.blit(title, (350, 50))
        
        card_w = 250
        card_h = 400
        start_x = 80
        start_y = 120
        
        mouse_pos = pygame.mouse.get_pos()
        
        for i, cls in enumerate(CLASSES):
            card_x = start_x + i * (card_w + 30)
            card_rect = pygame.Rect(card_x, start_y, card_w, card_h)
            
            is_selected = self.input_state['selected_class'] == i
            border_color = GOLD if is_selected else (100, 100, 100)
            if card_rect.collidepoint(mouse_pos):
                border_color = WHITE
                
            pygame.draw.rect(self.screen, (40, 40, 60), card_rect)
            pygame.draw.rect(self.screen, border_color, card_rect, 3)
            
            pygame.draw.rect(self.screen, cls['color'], (card_x + 50, start_y + 20, 150, 150))
            
            text = self.large_font.render(cls['name'], True, cls['color'])
            self.screen.blit(text, (card_x + 60, start_y + 190))
            
            stats = [
                f"HP: {cls['hp']}",
                f"MP: {cls['mana']}",
                f"ATK: {cls['attack']}",
                f"DEF: {cls['defense']}",
                f"SPD: {cls['speed']}",
            ]
            
            for j, stat in enumerate(stats):
                text = self.font.render(stat, True, WHITE)
                self.screen.blit(text, (card_x + 20, start_y + 240 + j * 25))
                
        btn_rect = pygame.Rect(500, 550, 200, 50)
        pygame.draw.rect(self.screen, (0, 150, 0), btn_rect)
        pygame.draw.rect(self.screen, GOLD, btn_rect, 2)
        text = self.large_font.render('ENTER DUNGEON', True, WHITE)
        self.screen.blit(text, (btn_rect.x + 25, btn_rect.y + 10))
        
    def render_game_over(self):
        self.screen.fill((40, 0, 0))
        
        title = self.title_font.render('GAME OVER', True, (255, 0, 0))
        self.screen.blit(title, (420, 150))
        
        player = self.game_state.get('player', {})
        stats = [
            f"Final Score: {self.game_state.get('score', 0)}",
            f"Floor Reached: {self.game_state.get('floor', 1)}",
            f"Turns Survived: {self.game_state.get('turn', 0)}",
            f"Gold Collected: {player.get('gold', 0)}",
            f"Monsters Killed: {self.game_state.get('kills', 0)}",
        ]
        
        for i, stat in enumerate(stats):
            text = self.large_font.render(stat, True, WHITE)
            self.screen.blit(text, (400, 280 + i * 45))
            
        btn_rect = pygame.Rect(500, 550, 200, 50)
        pygame.draw.rect(self.screen, (100, 100, 100), btn_rect)
        text = self.large_font.render('RETURN TO MENU', True, WHITE)
        self.screen.blit(text, (btn_rect.x + 15, btn_rect.y + 10))
        
    def handle_events(self):
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                self.running = False
                
            elif event.type == pygame.KEYDOWN:
                if self.ui_state == 'chat_input':
                    if event.key == pygame.K_RETURN:
                        if self.input_state['chat_input']:
                            asyncio.create_task(self.send_message('chat', {'text': self.input_state['chat_input']}))
                        self.input_state['chat_input'] = ''
                        self.ui_state = 'gameplay'
                    elif event.key == pygame.K_BACKSPACE:
                        self.input_state['chat_input'] = self.input_state['chat_input'][:-1]
                    else:
                        if len(self.input_state['chat_input']) < 100:
                            self.input_state['chat_input'] += event.unicode
                            
                elif self.input_state['login_mode'] in ['login', 'register']:
                    target = 'username' if self.input_state['login_mode'] == 'login' else 'username'
                    if event.key == pygame.K_TAB:
                        pass
                    elif event.key == pygame.K_BACKSPACE:
                        if target == 'username':
                            self.input_state['username'] = self.input_state['username'][:-1]
                        else:
                            self.input_state['password'] = self.input_state['password'][:-1]
                    elif event.key == pygame.K_RETURN:
                        asyncio.create_task(self.send_message(self.input_state['login_mode'], {
                            'username': self.input_state['username'],
                            'password': self.input_state['password']
                        }))
                    else:
                        if target == 'username':
                            self.input_state['username'] += event.unicode
                        else:
                            self.input_state['password'] += event.unicode
                            
                elif self.ui_state == 'gameplay':
                    if event.key in [pygame.K_w, pygame.K_UP]:
                        asyncio.create_task(self.send_message('move', {'dx': 0, 'dy': -1}))
                    elif event.key in [pygame.K_s, pygame.K_DOWN]:
                        asyncio.create_task(self.send_message('move', {'dx': 0, 'dy': 1}))
                    elif event.key in [pygame.K_a, pygame.K_LEFT]:
                        asyncio.create_task(self.send_message('move', {'dx': -1, 'dy': 0}))
                    elif event.key in [pygame.K_d, pygame.K_RIGHT]:
                        asyncio.create_task(self.send_message('move', {'dx': 1, 'dy': 0}))
                    elif event.key in [pygame.K_1, pygame.K_2, pygame.K_3, pygame.K_4]:
                        skill_idx = event.key - pygame.K_1
                        self.input_state['skill_targeting'] = skill_idx
                        self.input_state['target_mode'] = True
                    elif event.key == pygame.K_i:
                        self.ui_state = 'inventory'
                    elif event.key == pygame.K_e:
                        asyncio.create_task(self.send_message('interact', {}))
                    elif event.key == pygame.K_SPACE:
                        asyncio.create_task(self.send_message('end_turn', {}))
                    elif event.key == pygame.K_RETURN:
                        self.ui_state = 'chat_input'
                    elif event.key == pygame.K_ESCAPE:
                        if self.input_state['target_mode']:
                            self.input_state['target_mode'] = False
                            self.input_state['skill_targeting'] = None
                            
                elif self.ui_state == 'inventory':
                    if event.key == pygame.K_i:
                        self.ui_state = 'gameplay'
                    elif event.key in [pygame.K_1, pygame.K_2, pygame.K_3, pygame.K_4, pygame.K_5,
                                       pygame.K_6, pygame.K_7, pygame.K_8, pygame.K_9, pygame.K_0]:
                        slot = event.key - pygame.K_1 if event.key != pygame.K_0 else 9
                        if slot < len(self.game_state.get('inventory', [])):
                            asyncio.create_task(self.send_message('use_item', {'slot': slot}))
                            
            elif event.type == pygame.MOUSEBUTTONDOWN:
                if self.ui_state == 'menu':
                    pass
                elif self.ui_state == 'class_select':
                    card_w = 250
                    card_h = 400
                    start_x = 80
                    start_y = 120
                    for i in range(4):
                        card_x = start_x + i * (card_w + 30)
                        card_rect = pygame.Rect(card_x, start_y, card_w, card_h)
                        if card_rect.collidepoint(event.pos):
                            self.input_state['selected_class'] = i
                    btn_rect = pygame.Rect(500, 550, 200, 50)
                    if btn_rect.collidepoint(event.pos):
                        selected_cls = CLASSES[self.input_state['selected_class']]
                        asyncio.create_task(self.send_message('select_class', {'class_name': selected_cls['name']}))
                        self.ui_state = 'gameplay'
                elif self.ui_state == 'gameplay' and self.input_state['target_mode']:
                    offset_x = 10
                    offset_y = 10
                    x = (event.pos[0] - offset_x) // TILE_SIZE
                    y = (event.pos[1] - offset_y) // TILE_SIZE
                    skill_idx = self.input_state['skill_targeting']
                    asyncio.create_task(self.send_message('use_skill', {'skill_index': skill_idx, 'x': x, 'y': y}))
                    self.input_state['target_mode'] = False
                    self.input_state['skill_targeting'] = None
                elif self.ui_state == 'inventory':
                    inv_x = 200
                    inv_y = 100
                    slot_size = 50
                    for i in range(20):
                        col = i % 5
                        row = i // 5
                        slot_x = inv_x + col * slot_size
                        slot_y = inv_y + row * slot_size
                        slot_rect = pygame.Rect(slot_x, slot_y, slot_size - 2, slot_size - 2)
                        if slot_rect.collidepoint(event.pos):
                            if i < len(self.game_state.get('inventory', [])):
                                self.input_state['selected_slot'] = i
                elif self.ui_state == 'game_over':
                    btn_rect = pygame.Rect(500, 550, 200, 50)
                    if btn_rect.collidepoint(event.pos):
                        self.ui_state = 'menu'
                        
    def update(self):
        self.process_messages()
        
    def render(self):
        self.screen.fill((0, 0, 0))
        
        if self.ui_state == 'menu':
            self.render_menu()
        elif self.ui_state == 'class_select':
            self.render_class_select()
        elif self.ui_state == 'gameplay':
            self.render_map()
            self.render_entities()
            self.render_hud()
            self.render_combat_log()
            self.render_chat()
            self.render_entity_tooltip()
            if self.input_state['target_mode']:
                text = self.large_font.render('Select target... (ESC to cancel)', True, GOLD)
                self.screen.blit(text, (400, 750))
        elif self.ui_state == 'inventory':
            self.render_map()
            self.render_entities()
            self.render_hud()
            self.render_inventory()
        elif self.ui_state == 'game_over':
            self.render_game_over()
        elif self.ui_state == 'chat_input':
            self.render_map()
            self.render_entities()
            self.render_hud()
            self.render_combat_log()
            self.render_chat()
            
        pygame.display.flip()
        
    async def game_loop(self):
        while self.running:
            self.handle_events()
            self.update()
            self.render()
            self.clock.tick(60)
            await asyncio.sleep(0.01)
            
    async def run(self, host='localhost', port=8765):
        await self.connect(host, port)
        
        receive_task = asyncio.create_task(self.receive_loop())
        game_task = asyncio.create_task(self.game_loop())
        
        await asyncio.gather(receive_task, game_task)
        
        if self.websocket:
            await self.websocket.close()
        pygame.quit()

if __name__ == '__main__':
    client = GameClient()
    asyncio.run(client.run())
