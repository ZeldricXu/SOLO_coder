package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
)

func main() {
	port := flag.Int("port", 8080, "port to listen on")
	dir := flag.String("dir", "dist", "directory to serve files from")
	flag.Parse()

	absDir, err := filepath.Abs(*dir)
	if err != nil {
		log.Fatalf("failed to resolve directory: %v", err)
	}

	info, err := os.Stat(absDir)
	if err != nil || !info.IsDir() {
		log.Fatalf("directory %s does not exist or is not a directory", absDir)
	}

	handler := &fileServer{root: absDir}

	addr := ":" + strconv.Itoa(*port)
	log.Printf("Serving %s on http://localhost%s", absDir, addr)
	if err := http.ListenAndServe(addr, handler); err != nil {
		log.Fatalf("server error: %v", err)
	}
}

type fileServer struct {
	root string
}

var contentTypes = map[string]string{
	".wasm": "application/wasm",
	".js":   "application/javascript",
	".html": "text/html; charset=utf-8",
	".css":  "text/css",
	".json": "application/json",
	".gz":   "application/gzip",
}

func (fs *fileServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cross-Origin-Opener-Policy", "same-origin")
	w.Header().Set("Cross-Origin-Embedder-Policy", "require-corp")
	w.Header().Set("Cross-Origin-Resource-Policy", "cross-origin")

	cleanPath := filepath.Clean("/" + r.URL.Path)
	fullPath := filepath.Join(fs.root, cleanPath)

	gzPath := fullPath + ".gz"
	if _, err := os.Stat(gzPath); err == nil {
		ext := filepath.Ext(fullPath)
		if ct, ok := contentTypes[ext]; ok {
			w.Header().Set("Content-Type", ct)
		}
		w.Header().Set("Content-Encoding", "gzip")
		http.ServeFile(w, r, gzPath)
		fs.logRequest(r.Method, r.URL.Path, http.StatusOK)
		return
	}

	if ct, ok := contentTypes[filepath.Ext(fullPath)]; ok {
		w.Header().Set("Content-Type", ct)
	}

	http.ServeFile(w, r, fullPath)
	fs.logRequest(r.Method, r.URL.Path, http.StatusOK)
}

func (fs *fileServer) logRequest(method, path string, status int) {
	log.Printf("%s %s %d", method, path, status)
}
