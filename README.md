🕷️ Crawler

Apache Pekko-based web crawler using Playwright to extract structured text and link data from dynamic websites.

This project combines the concurrency model of Apache Pekko with the browser automation power of Microsoft Playwright to build a scalable, 
actor-based scraping system. 

It supports:

- Headless browser automation
- DOM content extraction
- Click-based interaction (e.g. expand buttons)
- Retry logic and error handling
- Parallel scraping via actor supervision
- Proxy support for IP rotation
  see [Proxy Configuration](src/main/resources/application.conf) for details
---
🚧 Project Status: Work in Progress

This project is still under active development.
You can try it out by running the tests provided in the tests/ folder to see the current scraping logic and data extraction behavior in action.

🎥 Scrapiong in Action
https://github.com/user-attachments/assets/2a466d0a-dacc-4478-b571-b12556a7bdc8

