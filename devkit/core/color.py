import sys
from colorama import init, Fore, Back, Style

init(autoreset=True)


class Color:
    BLACK = Fore.BLACK
    RED = Fore.RED
    GREEN = Fore.GREEN
    YELLOW = Fore.YELLOW
    BLUE = Fore.BLUE
    MAGENTA = Fore.MAGENTA
    CYAN = Fore.CYAN
    WHITE = Fore.WHITE
    RESET = Fore.RESET

    BG_BLACK = Back.BLACK
    BG_RED = Back.RED
    BG_GREEN = Back.GREEN
    BG_YELLOW = Back.YELLOW
    BG_BLUE = Back.BLUE
    BG_MAGENTA = Back.MAGENTA
    BG_CYAN = Back.CYAN
    BG_WHITE = Back.WHITE

    BOLD = Style.BRIGHT
    DIM = Style.DIM
    UNDERLINE = '\033[4m'

    @staticmethod
    def wrap(text, color):
        return f'{color}{text}{Style.RESET_ALL}'

    @staticmethod
    def info(text):
        return Color.wrap(text, Color.CYAN)

    @staticmethod
    def success(text):
        return Color.wrap(text, Color.GREEN)

    @staticmethod
    def warning(text):
        return Color.wrap(text, Color.YELLOW)

    @staticmethod
    def error(text):
        return Color.wrap(text, Color.RED)

    @staticmethod
    def highlight(text):
        return Color.wrap(text, Color.MAGENTA)

    @staticmethod
    def bold(text):
        return Color.wrap(text, Color.BOLD)


def cprint(text, color=None, bg=None, bold=False, file=None, nl=True):
    """Print colored text to the console.
    
    Wraps the given text in ANSI escape sequences for color and formatting,
    then prints it. Uses colorama for cross-platform Windows support.
    
    Args:
        text: The text to print. Will be converted to str if not already.
        color: Foreground color (use Color.RED, Color.GREEN, etc.)
        bg: Background color (use Color.BG_RED, Color.BG_GREEN, etc.)
        bold: If True, makes text bold.
        file: Output file object. Defaults to sys.stdout.
        nl: If True (default), adds newline at end.
    
    Examples:
        >>> cprint('Success!', Color.GREEN)
        >>> cprint('Warning', Color.YELLOW, bold=True)
        >>> cprint('Error', bg=Color.BG_RED)
    """
    output = str(text)
    if bold:
        output = Color.wrap(output, Color.BOLD)
    if color:
        output = Color.wrap(output, color)
    if bg:
        output = Color.wrap(output, bg)
    print(output, file=file or sys.stdout, end='\n' if nl else '')
