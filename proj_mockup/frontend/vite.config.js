import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            {
              name: 'react-vendor',
              test: /[\\/]node_modules[\\/](react|react-dom|scheduler)[\\/]/,
              priority: 30
            },
            {
              name: 'katex-vendor',
              test: /[\\/]node_modules[\\/]katex[\\/]/,
              priority: 25
            },
            {
              name: 'markdown-vendor',
              test: /[\\/]node_modules[\\/](react-markdown|remark-.*|rehype-.*|unified|micromark.*|mdast-.*|hast-.*|unist-.*|vfile.*|bail|trough|is-plain-obj|devlop|property-information|space-separated-tokens|comma-separated-tokens|html-url-attributes|decode-named-character-reference|character-entities.*|ccount|escape-string-regexp|markdown-table|longest-streak|zwitch)[\\/]/,
              priority: 20
            },
            {
              name: 'icons-vendor',
              test: /[\\/]node_modules[\\/]lucide-react[\\/]/,
              priority: 15
            }
          ]
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
