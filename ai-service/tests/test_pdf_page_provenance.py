import asyncio
import io

import pytest
from pypdf import PdfReader, PdfWriter

from app.services.document_extraction import AssessmentDocumentInvalidError, AssessmentDocumentWorkerPool
from tests.test_document_extraction import extractor, pdf_bytes


def multipage_pdf():
    writer = PdfWriter()
    writer.add_page(PdfReader(io.BytesIO(pdf_bytes(b'BT /F1 12 Tf 72 720 Td (First page) Tj ET'))).pages[0])
    writer.add_blank_page(width=612, height=792)
    writer.add_page(PdfReader(io.BytesIO(pdf_bytes(b'BT /F1 12 Tf 72 720 Td (Third page) Tj ET'))).pages[0])
    result = io.BytesIO()
    writer.write(result)
    return result.getvalue()


def test_pdf_physical_pages_survive_blank_pages_and_worker():
    content = multipage_pdf()
    direct = extractor().extract(filename='source.pdf', content=content)
    assert direct.text == 'First page\n\nThird page'
    assert direct.line_pages == (1, None, 3)
    isolated = asyncio.run(AssessmentDocumentWorkerPool().extract(extractor(), filename='source.pdf', content=content))
    assert isolated == direct


def test_pdf_without_text_fails_instead_of_inventing_ocr():
    with pytest.raises(AssessmentDocumentInvalidError, match='OCR'):
        extractor().extract(filename='scanned.pdf', content=pdf_bytes(b''))


def test_non_pdf_has_no_physical_page_numbers():
    document = extractor().extract(filename='source.md', content=b'First\n\nThird')
    assert document.line_pages == ()


def test_verified_evidence_lines_control_draft_source_pages():
    from app.agents.graph_ingestion import GraphIngestionAgent
    from app.contracts.graph_ingestion import GraphExtractorProviderOutput, GraphExtractorOutput, GraphCandidateReview
    from tests.test_graph_ingestion_agent import extractor_output, reviewer_output, request

    generated = GraphExtractorProviderOutput.model_validate(extractor_output())
    lines = ('函数使用 def 定义。', '', '第三页')
    evidence, _ = GraphIngestionAgent._restore_and_validate_evidence(
        generated, request=request(), document_lines=lines, source_hash='a' * 64,
        allow_line_fallback=False, line_pages=(1, None, 3))
    assert evidence[0].page_from == 1 and evidence[0].page_to == 1
    extracted = GraphExtractorOutput(nodes=generated.nodes, edges=generated.edges, sources=evidence)
    sources = GraphIngestionAgent._reviewed_sources(request(), extracted,
        {r['candidate_id']: GraphCandidateReview.model_validate(r) for r in reviewer_output()['reviews']},
        node_id_by_candidate={n.candidate_id: n.candidate_id for n in extracted.nodes}, source_hash='a' * 64)
    assert sources and all(s.page_from == 1 and s.page_to == 1 for s in sources)
    # The same excerpt on a later physical page resolves from parser metadata.
    evidence, _ = GraphIngestionAgent._restore_and_validate_evidence(
        generated, request=request(), document_lines=lines, source_hash='a' * 64,
        allow_line_fallback=False, line_pages=(3, None, 8))
    assert evidence[0].page_from == 3


def test_pdf_indirect_encoding_difference_names_are_resolved():
    from pypdf.generic import ArrayObject, DictionaryObject, NameObject, NumberObject
    reader = PdfReader(io.BytesIO(pdf_bytes(b'BT /F1 12 Tf 72 720 Td (A) Tj ET')))
    writer = PdfWriter()
    writer.add_page(reader.pages[0])
    font = writer.pages[0]['/Resources']['/Font']['/F1']
    font[NameObject('/Encoding')] = DictionaryObject({
        NameObject('/BaseEncoding'): NameObject('/WinAnsiEncoding'),
        NameObject('/Differences'): ArrayObject([NumberObject(65), writer._add_object(NameObject('/A'))]),
    })
    content = io.BytesIO()
    writer.write(content)
    document = extractor().extract(filename='indirect-font.pdf', content=content.getvalue())
    assert document.text == 'A' and document.line_pages == (1,)
    isolated = asyncio.run(AssessmentDocumentWorkerPool().extract(
        extractor(), filename='indirect-font.pdf', content=content.getvalue()))
    assert isolated == document
